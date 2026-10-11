-- Conta settlement do tenant: o que o banco já sabe responder. Só leitura.
-- Rodar:  cat settlement_check.sql | docker exec -i wallet-core-postgres-1 psql -U wallet_owner -d wallet
-- (wallet_owner é superusuário no ambiente local, então enxerga todos os tenants apesar do RLS.)
\pset footer off
\pset null '-'

\echo
\echo '== 1. Posição de cada tenant: settlement x clientes (a soma tem que dar zero)'
SELECT t.client_id                                                        AS tenant,
       to_char(-SUM(a.balance_cents) FILTER (WHERE a.kind = 'SETTLEMENT') / 100.0, 'FM999G999G990D00') AS dinheiro_no_tenant,
       to_char( SUM(a.balance_cents) FILTER (WHERE a.kind = 'CUSTOMER')   / 100.0, 'FM999G999G990D00') AS soma_saldos_clientes,
       SUM(a.balance_cents)                                               AS diferenca_cents,
       COUNT(*) FILTER (WHERE a.kind = 'CUSTOMER')                        AS contas_clientes
FROM account a JOIN tenant t ON t.id = a.tenant_id
GROUP BY t.client_id ORDER BY t.client_id;

\echo
\echo '== 2. As shards da settlement (saldo negativo = dinheiro devido aos clientes)'
SELECT t.client_id AS tenant, a.id AS shard,
       to_char(a.balance_cents / 100.0, 'FM999G999G990D00') AS saldo,
       (SELECT COUNT(*) FROM ledger_entry e WHERE e.account_id = a.id) AS lancamentos
FROM account a JOIN tenant t ON t.id = a.tenant_id
WHERE a.kind = 'SETTLEMENT'
ORDER BY t.client_id, a.id;

\echo
\echo '== 3. Por tipo de transação: o que passa pela settlement (do ponto de vista do tenant)'
\echo '   entrada = o tenant recebeu dinheiro de fora (a settlement foi debitada); saída = o contrário'
SELECT t.client_id AS tenant, e.type AS tipo,
       CASE e.direction WHEN 'DEBIT' THEN 'entrada' ELSE 'saída' END AS sentido,
       COUNT(*) AS qtd,
       to_char(SUM(e.amount_cents) / 100.0, 'FM999G999G990D00') AS total
FROM ledger_entry e
JOIN account a ON a.id = e.account_id AND a.kind = 'SETTLEMENT'
JOIN tenant t ON t.id = e.tenant_id
GROUP BY t.client_id, e.type, e.direction
ORDER BY t.client_id, sentido, e.type;

\echo
\echo '== 4. Conferência: transferências entre clientes NÃO passam pela settlement (esperado: 0)'
SELECT COUNT(*) AS transferencias_na_settlement
FROM ledger_entry e JOIN account a ON a.id = e.account_id AND a.kind = 'SETTLEMENT'
WHERE e.type = 'TRANSFER';

\echo
\echo '== 5. Extrato da settlement, só entradas (depósito, Pix recebido, devolução recebida), mais recentes primeiro'
\echo '   as 4 shards intercaladas por data; "cliente" é quem recebeu o dinheiro'
SELECT t.client_id AS tenant,
       to_char(e.occurred_at AT TIME ZONE 'America/Sao_Paulo', 'DD/MM/YYYY HH24:MI') AS quando,
       e.type AS tipo,
       to_char(e.amount_cents / 100.0, 'FM999G999G990D00') AS valor,
       c.name AS cliente,
       ca.branch || ' / ' || ca.account_number || '-' || ca.check_digit AS conta,
       left(e.description, 50) AS descricao
FROM ledger_entry e
JOIN account a  ON a.id = e.account_id AND a.kind = 'SETTLEMENT'
JOIN tenant t   ON t.id = e.tenant_id
LEFT JOIN account ca ON ca.id = e.counterparty_account_id
LEFT JOIN customer c ON c.id = ca.customer_id
WHERE e.direction = 'DEBIT'
ORDER BY e.occurred_at DESC, e.id DESC
LIMIT 30;

\echo
\echo '== 6. Entradas por dia (cash-in diário do tenant)'
SELECT t.client_id AS tenant,
       (e.occurred_at AT TIME ZONE 'America/Sao_Paulo')::date AS dia,
       COUNT(*) FILTER (WHERE e.type = 'DEPOSIT')        AS depositos,
       COUNT(*) FILTER (WHERE e.type = 'PIX_IN')         AS pix_recebidos,
       COUNT(*) FILTER (WHERE e.type = 'PIX_RETURN_IN')  AS devolucoes,
       to_char(SUM(e.amount_cents) / 100.0, 'FM999G999G990D00') AS total_entradas
FROM ledger_entry e
JOIN account a ON a.id = e.account_id AND a.kind = 'SETTLEMENT'
JOIN tenant t  ON t.id = e.tenant_id
WHERE e.direction = 'DEBIT'
GROUP BY t.client_id, dia
ORDER BY t.client_id, dia DESC;

\echo
\echo '== 7. A posição reconstruída pelo ledger bate com o saldo das shards? (esperado: diferença 0)'
SELECT t.client_id AS tenant,
       SUM(CASE e.direction WHEN 'DEBIT' THEN e.amount_cents ELSE -e.amount_cents END) AS entradas_menos_saidas_cents,
       (SELECT -SUM(balance_cents) FROM account x WHERE x.tenant_id = t.id AND x.kind = 'SETTLEMENT') AS posicao_cents,
       SUM(CASE e.direction WHEN 'DEBIT' THEN e.amount_cents ELSE -e.amount_cents END)
         - (SELECT -SUM(balance_cents) FROM account x WHERE x.tenant_id = t.id AND x.kind = 'SETTLEMENT') AS diferenca
FROM ledger_entry e
JOIN account a ON a.id = e.account_id AND a.kind = 'SETTLEMENT'
JOIN tenant t  ON t.id = e.tenant_id
GROUP BY t.id, t.client_id
ORDER BY t.client_id;
