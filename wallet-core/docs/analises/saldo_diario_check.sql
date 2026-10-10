-- Saldo de fechamento do dia (foto à meia-noite de Brasília): o que o ledger já permite. Só leitura.
-- Rodar:  cat saldo_diario_check.sql | docker exec -i wallet-core-postgres-1 psql -U wallet_owner -d wallet
\pset footer off
\pset null '-'

\echo
\echo '== 1. O último lançamento de cada conta bate com o saldo da conta? (base de tudo: esperado 0 divergências)'
SELECT COUNT(*) AS contas_cliente,
       COUNT(*) FILTER (WHERE last.balance_after_cents IS DISTINCT FROM a.balance_cents
                          AND NOT (last.balance_after_cents IS NULL AND a.balance_cents = 0)) AS divergencias
FROM account a
LEFT JOIN LATERAL (
  SELECT e.balance_after_cents FROM ledger_entry e
  WHERE e.account_id = a.id ORDER BY e.sequence_no DESC LIMIT 1
) last ON true
WHERE a.kind = 'CUSTOMER';

\echo
\echo '== 2. Data do fato x data do lançamento: existe lançamento retroativo? (occurred_at bem antes de created_at)'
\echo '   Se existir, um dia já "fechado" pode mudar depois da meia-noite.'
SELECT COUNT(*) AS lancamentos,
       COUNT(*) FILTER (WHERE created_at - occurred_at > interval '1 minute') AS retroativos_mais_de_1min,
       COUNT(*) FILTER (WHERE (occurred_at AT TIME ZONE 'America/Sao_Paulo')::date
                           <> (created_at  AT TIME ZONE 'America/Sao_Paulo')::date) AS em_outro_dia,
       max(created_at - occurred_at) AS maior_atraso
FROM ledger_entry;

\echo
\echo '== 3. A ordem do dia: a sequência da conta segue a data do fato? (esperado 0)'
\echo '   Se não seguir, "o último lançamento do dia" depende de qual ordem se usa.'
SELECT COUNT(*) AS fora_de_ordem
FROM ledger_entry e
JOIN ledger_entry p ON p.account_id = e.account_id AND p.sequence_no = e.sequence_no - 1
WHERE e.occurred_at < p.occurred_at;

\echo
\echo '== 4. Saldo de fechamento de cada conta, dia a dia (últimos 7 dias, meia-noite de Brasília)'
\echo '   Dia sem movimento repete o saldo anterior; antes do primeiro lançamento, a conta ainda não existia.'
WITH dias AS (
  SELECT d::date AS dia,
         ((d::date + 1)::timestamp AT TIME ZONE 'America/Sao_Paulo') AS fim_do_dia   -- meia-noite seguinte, em UTC
  FROM generate_series((now() AT TIME ZONE 'America/Sao_Paulo')::date - 6,
                       (now() AT TIME ZONE 'America/Sao_Paulo')::date, interval '1 day') d
)
SELECT t.client_id AS tenant, c.name AS cliente,
       ca.account_number || '-' || ca.check_digit AS conta,
       d.dia,
       CASE WHEN ca.created_at >= d.fim_do_dia THEN '(não existia)'
            ELSE to_char(COALESCE(f.balance_after_cents, 0) / 100.0, 'FM999G990D00') END AS saldo_fechamento,
       COALESCE(m.movimentos, 0) AS movimentos_no_dia
FROM account ca
JOIN customer c ON c.id = ca.customer_id
JOIN tenant t ON t.id = ca.tenant_id
CROSS JOIN dias d
LEFT JOIN LATERAL (
  SELECT e.balance_after_cents FROM ledger_entry e
  WHERE e.account_id = ca.id AND e.occurred_at < d.fim_do_dia
  ORDER BY e.sequence_no DESC LIMIT 1
) f ON true
LEFT JOIN LATERAL (
  SELECT COUNT(*) AS movimentos FROM ledger_entry e
  WHERE e.account_id = ca.id AND e.occurred_at <  d.fim_do_dia
                             AND e.occurred_at >= d.fim_do_dia - interval '1 day'
) m ON true
WHERE ca.kind = 'CUSTOMER'
  AND EXISTS (SELECT 1 FROM ledger_entry e WHERE e.account_id = ca.id)
ORDER BY t.client_id, c.name, conta, d.dia;

\echo
\echo '== 5. Saldo em 31/12 (o do imposto de renda): o mesmo cálculo para uma data qualquer'
\echo '   Exemplo com 31/12/2025 (antes do ambiente existir: tudo zerado) e com ontem.'
SELECT t.client_id AS tenant, c.name AS cliente, ref.data,
       to_char(COALESCE((SELECT e.balance_after_cents FROM ledger_entry e
                         WHERE e.account_id = ca.id
                           AND e.occurred_at < ((ref.data + 1)::timestamp AT TIME ZONE 'America/Sao_Paulo')
                         ORDER BY e.sequence_no DESC LIMIT 1), 0) / 100.0, 'FM999G990D00') AS saldo
FROM account ca
JOIN customer c ON c.id = ca.customer_id
JOIN tenant t ON t.id = ca.tenant_id
CROSS JOIN (VALUES (date '2025-12-31'), ((now() AT TIME ZONE 'America/Sao_Paulo')::date - 1)) ref(data)
WHERE ca.kind = 'CUSTOMER' AND EXISTS (SELECT 1 FROM ledger_entry e WHERE e.account_id = ca.id)
ORDER BY t.client_id, c.name, ref.data;

\echo
\echo '== 6. Custo: quantos lançamentos o cálculo percorre, e o tamanho de uma foto diária guardada'
SELECT (SELECT COUNT(*) FROM ledger_entry)                                   AS lancamentos_no_ledger,
       (SELECT COUNT(*) FROM account WHERE kind = 'CUSTOMER')                AS contas_cliente,
       (SELECT COUNT(*) FROM account WHERE kind = 'CUSTOMER') * 365          AS linhas_por_ano_se_guardar_todo_dia,
       (SELECT COUNT(DISTINCT (account_id, (occurred_at AT TIME ZONE 'America/Sao_Paulo')::date))
          FROM ledger_entry e JOIN account a ON a.id = e.account_id AND a.kind = 'CUSTOMER') AS linhas_se_guardar_so_dias_com_movimento;
