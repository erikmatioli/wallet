-- Saldo de fechamento sob demanda, sem foto guardada. Só leitura: a única coisa criada é uma função
-- temporária (pg_temp), que some quando a sessão termina.
-- Rodar:  cat saldo_sob_demanda_check.sql | docker exec -i wallet-core-postgres-1 psql -U wallet_owner -d wallet
\pset footer off
\pset null '-'

-- A "verdade" de referência: o saldo depois do último lançamento da conta antes do instante.
-- (Busca direta, ordenando pela data. É o que o índice novo faria numa leitura só.)

\echo
\echo '== 0. As premissas (esperado: 0 e 0)'
SELECT (SELECT COUNT(*) FROM ledger_entry e JOIN ledger_entry p
          ON p.account_id = e.account_id AND p.sequence_no = e.sequence_no - 1
        WHERE e.occurred_at < p.occurred_at) AS sequencia_fora_da_ordem_da_data,
       (SELECT COUNT(*) FROM ledger_entry WHERE created_at - occurred_at > interval '1 minute') AS lancamentos_retroativos;

-- ------------------------------------------------------------------ forma 1: o saldo do dia no extrato
\echo
\echo '== 1. Saldo do dia pelo extrato: a primeira linha de cada dia (extrato vem do mais novo para o mais antigo)'
\echo '   comparado com a referência (último lançamento antes da meia-noite seguinte). Esperado: confere = sim em todas.'
WITH extrato AS (
  SELECT e.account_id, e.sequence_no, e.balance_after_cents,
         (e.occurred_at AT TIME ZONE 'America/Sao_Paulo')::date AS dia,
         row_number() OVER (PARTITION BY e.account_id, (e.occurred_at AT TIME ZONE 'America/Sao_Paulo')::date
                            ORDER BY e.sequence_no DESC) AS posicao_no_dia
  FROM ledger_entry e JOIN account a ON a.id = e.account_id AND a.kind = 'CUSTOMER'
)
SELECT c.name AS cliente, x.dia,
       to_char(x.balance_after_cents / 100.0, 'FM999G990D00') AS saldo_pelo_extrato,
       to_char(ref.balance_after_cents / 100.0, 'FM999G990D00') AS saldo_referencia,
       CASE WHEN x.balance_after_cents = ref.balance_after_cents THEN 'sim' ELSE 'NÃO' END AS confere
FROM extrato x
JOIN account ca ON ca.id = x.account_id
JOIN customer c ON c.id = ca.customer_id
CROSS JOIN LATERAL (
  SELECT e.balance_after_cents FROM ledger_entry e
  WHERE e.account_id = x.account_id
    AND e.occurred_at < ((x.dia + 1)::timestamp AT TIME ZONE 'America/Sao_Paulo')
  ORDER BY e.occurred_at DESC, e.sequence_no DESC LIMIT 1
) ref
WHERE x.posicao_no_dia = 1
ORDER BY c.name, x.dia;

\echo
\echo '== 1b. E com o extrato paginado? Páginas de 3 linhas, como o app pediria: o dia aparece primeiro em que página,'
\echo '   e a linha em que ele aparece é a do fechamento? (o dia continuar na página seguinte não muda o fechamento)'
WITH paginado AS (
  SELECT e.account_id, e.sequence_no, e.balance_after_cents,
         (e.occurred_at AT TIME ZONE 'America/Sao_Paulo')::date AS dia,
         (row_number() OVER (PARTITION BY e.account_id ORDER BY e.sequence_no DESC) - 1) / 3 + 1 AS pagina,
         row_number() OVER (PARTITION BY e.account_id ORDER BY e.sequence_no DESC) AS linha
  FROM ledger_entry e JOIN account a ON a.id = e.account_id AND a.kind = 'CUSTOMER'
), primeira_aparicao AS (
  SELECT DISTINCT ON (account_id, dia) account_id, dia, pagina, balance_after_cents
  FROM paginado ORDER BY account_id, dia, linha
)
SELECT c.name AS cliente, p.dia, p.pagina AS pagina_em_que_o_dia_comeca,
       (SELECT MAX(pagina) FROM paginado q WHERE q.account_id = p.account_id AND q.dia = p.dia) AS ultima_pagina_do_dia,
       to_char(p.balance_after_cents / 100.0, 'FM999G990D00') AS saldo_do_dia_mostrado
FROM primeira_aparicao p
JOIN account ca ON ca.id = p.account_id JOIN customer c ON c.id = ca.customer_id
ORDER BY c.name, p.dia;

-- ------------------------------------------------------------------ forma 2: saldo numa data, por busca binária
CREATE FUNCTION pg_temp.saldo_em(conta uuid, ate timestamptz, OUT saldo_cents bigint, OUT sequencia bigint, OUT leituras int)
LANGUAGE plpgsql AS $$
DECLARE
  lo bigint := 1;
  hi bigint;
  meio bigint;
  quando timestamptz;
BEGIN
  -- A última sequência da conta (account.version) é o teto da busca: nenhuma leitura no ledger.
  SELECT version INTO hi FROM account WHERE id = conta;
  sequencia := 0; leituras := 0;
  -- Maior sequência com occurred_at < ate. Cada passo é uma leitura no índice (account_id, sequence_no).
  WHILE lo <= hi LOOP
    meio := (lo + hi) / 2;
    SELECT occurred_at INTO quando FROM ledger_entry WHERE account_id = conta AND sequence_no = meio;
    leituras := leituras + 1;
    IF quando < ate THEN sequencia := meio; lo := meio + 1; ELSE hi := meio - 1; END IF;
  END LOOP;
  IF sequencia = 0 THEN
    saldo_cents := 0;   -- nenhum lançamento antes da data
  ELSE
    SELECT balance_after_cents INTO saldo_cents FROM ledger_entry WHERE account_id = conta AND sequence_no = sequencia;
    leituras := leituras + 1;
  END IF;
END $$;

\echo
\echo '== 2. Busca binária x referência, em muitos instantes de corte por conta'
\echo '   Cortes: exatamente no instante de cada lançamento, 1 µs depois de cada um, de hora em hora no dia, e a meia-noite.'
\echo '   Esperado: divergencias = 0.'
WITH cortes AS (
  SELECT a.id AS conta, e.occurred_at AS corte FROM account a JOIN ledger_entry e ON e.account_id = a.id WHERE a.kind = 'CUSTOMER'
  UNION ALL
  SELECT a.id, e.occurred_at + interval '1 microsecond' FROM account a JOIN ledger_entry e ON e.account_id = a.id WHERE a.kind = 'CUSTOMER'
  UNION ALL
  SELECT a.id, h FROM account a
  CROSS JOIN generate_series(('2026-10-09'::timestamp AT TIME ZONE 'America/Sao_Paulo'),
                             ('2026-10-10'::timestamp AT TIME ZONE 'America/Sao_Paulo'), interval '1 hour') h
  WHERE a.kind = 'CUSTOMER'
), comparado AS (
  SELECT c.conta, c.corte, b.saldo_cents AS binaria, b.leituras,
         COALESCE((SELECT e.balance_after_cents FROM ledger_entry e
                   WHERE e.account_id = c.conta AND e.occurred_at < c.corte
                   ORDER BY e.occurred_at DESC, e.sequence_no DESC LIMIT 1), 0) AS referencia
  FROM cortes c CROSS JOIN LATERAL pg_temp.saldo_em(c.conta, c.corte) b
)
SELECT COUNT(*) AS cortes_testados,
       COUNT(*) FILTER (WHERE binaria <> referencia) AS divergencias,
       round(avg(leituras), 1) AS leituras_medias,
       MAX(leituras) AS leituras_max
FROM comparado;

\echo
\echo '== 3. Saldo de fechamento de 09/10 de cada conta, pela busca binária (como a rotina do IR faria com 31/12)'
SELECT c.name AS cliente, ca.account_number || '-' || ca.check_digit AS conta,
       ca.version AS lancamentos_da_conta,
       to_char(b.saldo_cents / 100.0, 'FM999G990D00') AS saldo_fim_de_09_10,
       b.sequencia AS ultima_sequencia_do_dia, b.leituras
FROM account ca JOIN customer c ON c.id = ca.customer_id
CROSS JOIN LATERAL pg_temp.saldo_em(ca.id, ('2026-10-10'::timestamp AT TIME ZONE 'America/Sao_Paulo')) b
WHERE ca.kind = 'CUSTOMER'
ORDER BY c.name;

\echo
\echo '== 4. Cada passo da busca usa o índice que já existe? (esperado: Index Scan em ledger_entry_account_id_sequence_no_key)'
SET enable_seqscan = off;   -- só nesta sessão: com 46 linhas o planner preferiria ler a tabela inteira
EXPLAIN (COSTS OFF)
SELECT occurred_at FROM ledger_entry
WHERE account_id = '01a120d4-f133-7728-bc54-cf829d9d5675' AND sequence_no = 7;

\echo
\echo '== 5. Quantas leituras a busca binária faria em contas grandes (log2 do número de lançamentos + 1)'
SELECT n AS lancamentos_na_conta,
       ceil(log(2, n + 1))::int + 1 AS leituras_no_pior_caso
FROM (VALUES (10), (1000), (365000), (3650000), (36500000)) v(n);
