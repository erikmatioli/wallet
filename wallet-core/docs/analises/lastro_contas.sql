-- Contas de lastro: o que a tesouraria acompanha, reconstruído do ledger de hoje. Só leitura.
-- Cada movimento de cliente que entra ou sai do tenant tem uma perna interna (hoje, a settlement). Aqui ela é
-- classificada pela conta de lastro que o movimento usa de verdade:
--   Pix (PIX_IN, PIX_OUT, estorno, devoluções) -> Conta PI          (liquidado no SPI)
--   Saque (WITHDRAWAL)                         -> Caixa / ATM       (papel-moeda entregue)
--   Depósito (DEPOSIT)                         -> A regularizar     (dinheiro criado; a tesouraria cobre depois)
--   TED e crédito ainda não existem no core; entrariam como Reservas/CL e Carteira de crédito.
-- Saldo de uma conta de lastro = o que o tenant deveria ter naquele lugar (entradas - saídas).
-- Rodar: cat lastro_contas.sql | docker exec -i wallet-core-postgres-1 psql -U wallet_owner -d wallet
\pset footer off

\echo
\echo '== 1. Saldo esperado em cada conta de lastro (soma = posição do tenant = soma dos clientes)'
WITH movimentos AS (
  SELECT e.tenant_id, e.occurred_at, e.type,
         CASE WHEN e.type LIKE 'PIX%' THEN '1 Conta PI'
              WHEN e.type = 'WITHDRAWAL' THEN '2 Caixa / ATM'
              WHEN e.type = 'DEPOSIT' THEN '3 A regularizar'
              ELSE '9 outro: ' || e.type END AS lastro,
         -- do ponto de vista do tenant: debitar a perna interna = entrou dinheiro naquele lastro
         CASE e.direction WHEN 'DEBIT' THEN e.amount_cents ELSE -e.amount_cents END AS valor
  FROM ledger_entry e JOIN account a ON a.id = e.account_id AND a.kind = 'SETTLEMENT'
)
SELECT t.client_id AS tenant, substr(lastro, 3) AS conta_de_lastro,
       to_char(SUM(valor) / 100.0, 'FM999G990D00') AS saldo_esperado,
       COUNT(*) AS movimentos
FROM movimentos m JOIN tenant t ON t.id = m.tenant_id
GROUP BY t.client_id, lastro
UNION ALL
SELECT t.client_id, '= posição (soma dos clientes)',
       to_char(SUM(a.balance_cents) / 100.0, 'FM999G990D00'), NULL
FROM account a JOIN tenant t ON t.id = a.tenant_id
WHERE a.kind = 'CUSTOMER' GROUP BY t.client_id
ORDER BY 1, 2;

\echo
\echo '== 2. O descoberto dia a dia: quanto foi criado (depósito), coberto (nenhuma cobertura registrada ainda) e o acumulado'
WITH dia AS (
  SELECT e.tenant_id, (e.occurred_at AT TIME ZONE 'America/Sao_Paulo')::date AS dia,
         SUM(e.amount_cents) FILTER (WHERE e.type = 'DEPOSIT') AS criado
  FROM ledger_entry e JOIN account a ON a.id = e.account_id AND a.kind = 'SETTLEMENT'
  GROUP BY 1, 2
)
SELECT t.client_id AS tenant, d.dia,
       to_char(COALESCE(d.criado, 0) / 100.0, 'FM999G990D00') AS criado_no_dia,
       '0,00' AS coberto_no_dia,
       to_char(SUM(COALESCE(d.criado, 0)) OVER (PARTITION BY d.tenant_id ORDER BY d.dia) / 100.0, 'FM999G990D00')
         AS descoberto_no_fim_do_dia
FROM dia d JOIN tenant t ON t.id = d.tenant_id
ORDER BY 1, 2;

\echo
\echo '== 3. Conta PI: entradas, saídas e o fluxo líquido por dia (o que a tesouraria precisaria ter aportado)'
SELECT t.client_id AS tenant, (e.occurred_at AT TIME ZONE 'America/Sao_Paulo')::date AS dia,
       to_char(SUM(e.amount_cents) FILTER (WHERE e.direction = 'DEBIT') / 100.0, 'FM999G990D00') AS entrou,
       to_char(SUM(e.amount_cents) FILTER (WHERE e.direction = 'CREDIT') / 100.0, 'FM999G990D00') AS saiu,
       to_char(SUM(CASE e.direction WHEN 'DEBIT' THEN e.amount_cents ELSE -e.amount_cents END) / 100.0,
               'FM999G990D00') AS liquido,
       to_char(MIN(run) / 100.0, 'FM999G990D00') AS menor_saldo_no_dia
FROM (
  SELECT e.*, SUM(CASE e.direction WHEN 'DEBIT' THEN e.amount_cents ELSE -e.amount_cents END)
                OVER (PARTITION BY e.tenant_id ORDER BY e.occurred_at, e.id) AS run
  FROM ledger_entry e JOIN account a ON a.id = e.account_id AND a.kind = 'SETTLEMENT'
  WHERE e.type LIKE 'PIX%'
) e JOIN tenant t ON t.id = e.tenant_id
GROUP BY t.client_id, dia ORDER BY 1, 2;
