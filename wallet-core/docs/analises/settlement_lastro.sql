-- Posição do tenant pela origem do dinheiro: com lastro no SPI (Pix) x sem lastro (depósito manual). Só leitura.
-- Rodar:  cat settlement_lastro.sql | docker exec -i wallet-core-postgres-1 psql -U wallet_owner -d wallet
\pset footer off
\pset null '-'

\echo
\echo '== A. Entradas e saídas do tenant, separadas pela origem'
\echo '   Pix enviado é contado LÍQUIDO dos estornos: um Pix recusado saiu e voltou, não saiu de verdade.'
WITH s AS (
  SELECT e.tenant_id, e.type, e.direction, e.amount_cents
  FROM ledger_entry e JOIN account a ON a.id = e.account_id AND a.kind = 'SETTLEMENT'
)
SELECT t.client_id AS tenant,
       to_char(COALESCE(SUM(amount_cents) FILTER (WHERE type IN ('PIX_IN','PIX_RETURN_IN')),0)/100.0, 'FM999G990D00') AS "entrada Pix (SPI)",
       to_char(COALESCE(SUM(amount_cents) FILTER (WHERE type = 'DEPOSIT'),0)/100.0, 'FM999G990D00')                  AS "entrada depósito (sem lastro)",
       to_char((COALESCE(SUM(amount_cents) FILTER (WHERE type IN ('PIX_OUT','PIX_RETURN_OUT')),0)
              - COALESCE(SUM(amount_cents) FILTER (WHERE type = 'PIX_REFUND'),0))/100.0, 'FM999G990D00')            AS "saída Pix líquida (SPI)",
       to_char(COALESCE(SUM(amount_cents) FILTER (WHERE type = 'WITHDRAWAL'),0)/100.0, 'FM999G990D00')              AS "saída saque",
       to_char(SUM(CASE direction WHEN 'DEBIT' THEN amount_cents ELSE -amount_cents END)/100.0, 'FM999G990D00')    AS "posição"
FROM s JOIN tenant t ON t.id = s.tenant_id
GROUP BY t.client_id ORDER BY t.client_id;

\echo
\echo '== B. Quanto da posição tem lastro'
\echo '   com lastro = Pix recebido - Pix enviado líquido; sem lastro = depósitos - saques'
\echo '   (um saque é tratado como saída do dinheiro sem lastro: o tenant entrega em espécie o que creditou à mão)'
WITH s AS (
  SELECT e.tenant_id, e.type, e.amount_cents
  FROM ledger_entry e JOIN account a ON a.id = e.account_id AND a.kind = 'SETTLEMENT'
), p AS (
  SELECT tenant_id,
         COALESCE(SUM(amount_cents) FILTER (WHERE type IN ('PIX_IN','PIX_RETURN_IN')),0)
       - COALESCE(SUM(amount_cents) FILTER (WHERE type IN ('PIX_OUT','PIX_RETURN_OUT')),0)
       + COALESCE(SUM(amount_cents) FILTER (WHERE type = 'PIX_REFUND'),0)              AS com_lastro,
         COALESCE(SUM(amount_cents) FILTER (WHERE type = 'DEPOSIT'),0)
       - COALESCE(SUM(amount_cents) FILTER (WHERE type = 'WITHDRAWAL'),0)              AS sem_lastro
  FROM s GROUP BY tenant_id
)
SELECT t.client_id AS tenant,
       to_char((com_lastro + sem_lastro)/100.0, 'FM999G990D00') AS posicao,
       to_char(com_lastro/100.0, 'FM999G990D00')                AS com_lastro_spi,
       to_char(sem_lastro/100.0, 'FM999G990D00')                AS sem_lastro_depositos,
       CASE WHEN com_lastro + sem_lastro = 0 THEN '-'
            ELSE round(100.0 * sem_lastro / (com_lastro + sem_lastro), 1) || '%' END AS parte_sem_lastro
FROM p JOIN tenant t ON t.id = p.tenant_id ORDER BY t.client_id;

\echo
\echo '== C. Os depósitos (dinheiro sem lastro), um a um'
\echo '   O que o ledger guarda sobre cada um: quando, quanto, para quem, a descrição e a chave de idempotência.'
SELECT t.client_id AS tenant,
       to_char(f.occurred_at AT TIME ZONE 'America/Sao_Paulo', 'DD/MM/YYYY HH24:MI') AS quando,
       to_char(f.amount_cents/100.0, 'FM999G990D00') AS valor,
       c.name AS cliente,
       ca.branch || ' / ' || ca.account_number || '-' || ca.check_digit AS conta,
       NULLIF(f.description, '') AS descricao,
       left(f.idempotency_key, 20) AS chave
FROM financial_transaction f
JOIN tenant t ON t.id = f.tenant_id
JOIN ledger_entry ce ON ce.transaction_id = f.id
JOIN account ca ON ca.id = ce.account_id AND ca.kind = 'CUSTOMER'
JOIN customer c ON c.id = ca.customer_id
WHERE f.type = 'DEPOSIT'
ORDER BY f.occurred_at DESC;

\echo
\echo '== D. Clientes com dinheiro sem lastro (depósitos recebidos x saldo atual)'
SELECT t.client_id AS tenant, c.name AS cliente,
       to_char(SUM(f.amount_cents)/100.0, 'FM999G990D00') AS depositos_recebidos,
       to_char(MAX(ca.balance_cents)/100.0, 'FM999G990D00') AS saldo_atual
FROM financial_transaction f
JOIN tenant t ON t.id = f.tenant_id
JOIN ledger_entry ce ON ce.transaction_id = f.id
JOIN account ca ON ca.id = ce.account_id AND ca.kind = 'CUSTOMER'
JOIN customer c ON c.id = ca.customer_id
WHERE f.type = 'DEPOSIT'
GROUP BY t.client_id, c.name ORDER BY SUM(f.amount_cents) DESC;
