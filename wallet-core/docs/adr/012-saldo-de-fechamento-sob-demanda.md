# ADR-012 — Saldo de fechamento do dia, calculado sob demanda

**Status:** proposta

## Contexto
Precisamos do **saldo de cada conta no fim de cada dia** (meia-noite de Brasília):

- **Imposto de renda:** o informe do cliente traz o saldo em 31/12 do ano anterior e do ano-base
  ("Bens e Direitos"). É uma rotina pontual, que sabe a data de que precisa.
- **Evolução da carteira:** o cliente quer ver como o saldo mudou dia a dia.

A escala a considerar é de **mais de 500 mil contas por tenant**, com contas de **mais de mil lançamentos
por dia**.

O ledger já tem o que é preciso. É imutável (trigger `forbid_mutation`), e cada lançamento guarda o saldo
da conta logo depois dele (`balance_after_cents`). O saldo de fechamento de um dia é o desse lançamento
no último lançamento da conta antes da meia-noite seguinte. Não é preciso somar nada.

Validado no banco local (10/10/2026, scripts só de leitura):

| Conferência | Resultado |
|---|---|
| Último lançamento de cada conta = saldo da conta | 8 contas, 0 divergências |
| Lançamento retroativo (`occurred_at` antes de `created_at`) | 0 de 46 |
| Sequência da conta fora da ordem de `occurred_at` | 0 |
| Saldo do dia pela 1ª linha de cada dia do extrato = referência | 6 de 6, também paginado de 3 em 3 |
| Busca binária na sequência = referência | 258 instantes de corte, 0 divergências, 2,2 leituras em média |

## Decisões

### 1. Nenhuma foto guardada: o saldo de fechamento sai do ledger quando é pedido
Uma foto diária guardada (um job à meia-noite gravando o saldo de cada conta) foi considerada e
descartada:

- o ledger já garante o valor, então a foto seria uma cópia a manter e a conciliar;
- o job teria de percorrer 500 mil contas toda noite, recuperar os dias em que não rodou e ter cuidado
  com a virada do dia, para entregar um dado que quase ninguém consulta;
- quem consulta (o IR, uma vez por ano; o cliente, de vez em quando) sabe a data de que precisa.

### 2. O dia é o de Brasília
O **saldo de fechamento do dia D** de uma conta é o `balance_after_cents` do último lançamento com
`occurred_at < D+1 00:00` em `America/Sao_Paulo`. Se não houver lançamento antes disso, o saldo é zero (a
conta ainda não existia ou não tinha movimento). Um dia sem lançamentos tem o saldo de fechamento do dia
anterior.

### 3. O saldo do dia no extrato: só nas telas
O extrato vem do mais novo para o mais antigo, e cada linha já traz o saldo depois dela. A **primeira
linha de cada dia** é o último lançamento do dia, e o saldo dela é o saldo de fechamento.

- O app desktop, o M-Wall e o console mostram "Saldo do dia: R$ X" no cabeçalho de cada dia do extrato.
  Nenhuma API muda.
- **Paginação:** um dia dividido entre páginas começa na página mais recente, que é a que mostra o
  cabeçalho, já com o saldo certo. As páginas seguintes só trazem lançamentos anteriores do mesmo dia.
- **Hoje:** o cabeçalho diz "Saldo até agora", porque o dia não fechou.

### 4. O saldo numa data: busca binária na sequência da conta, sem índice novo
`GET /v1/accounts/{id}/balance?at=2026-12-31` (escopo `accounts:read`)

```json
{ "accountId": "…", "date": "2026-12-31", "closingBalance": 1532.40, "lastSequence": 18233,
  "cutoff": "2027-01-01T03:00:00Z", "currency": "BRL" }
```

- **Como acha o lançamento:** a sequência da conta segue a ordem de `occurred_at` (decisão 6). Então o
  último lançamento antes do corte é a maior sequência com `occurred_at < corte`, achada por **busca
  binária** entre 1 e `account.version` (a última sequência, lida da conta). Cada passo é uma leitura no
  índice único que já existe, `(account_id, sequence_no)`.
- **Custo:** `log2(n) + 1` leituras: 11 para mil lançamentos, 20 para 365 mil (mil por dia durante um ano),
  27 para 36 milhões. Não depende de quantos lançamentos houve no dia.
- **Por que não um índice novo** em `(account_id, occurred_at)`: acharia o lançamento numa leitura só,
  mas cada lançamento (até 500 milhões por dia num tenant) escreveria uma entrada a mais, no caminho mais
  quente do sistema. A busca binária troca isso por cerca de 20 leituras numa consulta rara.
- **Só dias encerrados:** `at` de hoje ou do futuro responde `422 DAY_NOT_CLOSED`. "Encerrado" é a
  meia-noite seguinte mais uma **carência** (`wallet.balance.day-close-grace`, padrão 1 minuto): um
  lançamento com `occurred_at` às 23:59:59,9 pode terminar de gravar depois da meia-noite. A carência
  precisa ser maior que a transação mais longa que o core aceita.
- Um saldo de dia encerrado **nunca muda** (decisões 1 e 6): pode ser guardado em cache por quem consulta.

### 5. A evolução dia a dia
`GET /v1/accounts/{id}/daily-balances?from=2026-01-01&to=2026-12-31` (mesmo escopo; até 366 dias)

- Uma busca binária por dia, e cada uma começa onde a do dia anterior parou (a sequência só cresce): no
  pior caso, 366 × ~20 leituras, qualquer que seja o volume da conta.
- Responde um ponto por dia, dias sem movimento incluídos (repetem o saldo).
- O `app-api` expõe `GET /app/v1/balance-history?from=&to=` para a conta do token, e o app desktop e o
  M-Wall ganham o gráfico da evolução.

### 6. A garantia: a data de um lançamento nunca volta
Tudo acima depende de uma regra: **dentro de uma conta, `occurred_at` nunca diminui com a sequência**.
Hoje ela vale por acaso: o core grava a data com o próprio relógio (`clock.instant()`), e nenhuma API
aceita uma data de quem chama. Ela pode quebrar de dois jeitos:

- **Relógios de instâncias diferentes.** Duas instâncias do core com relógios a alguns milissegundos de
  distância podem lançar na mesma conta a sequência 6 com data anterior à da sequência 5.
- **Uma mudança futura** que aceite data informada (um lançamento "com data de ontem").

Por isso a regra passa a ser garantida na gravação:

- A conta ganha `last_occurred_at`, atualizada no mesmo `UPDATE` que já soma o saldo e a versão a cada
  lançamento (`JdbcAccountRepository`; a linha já está travada, então não há escrita nova):
  `last_occurred_at = GREATEST(last_occurred_at, :agora) … RETURNING last_occurred_at`. O lançamento
  dessa conta é gravado com a data devolvida. Um relógio atrasado não põe um lançamento antes do
  anterior; no máximo o empata. As duas pernas de uma transação podem ficar com datas a milissegundos de
  distância; a da transação (`financial_transaction.occurred_at`) é a maior delas.
- Um teste de integração tenta gravar fora de ordem e falha; um teste de arquitetura impede que a data do
  lançamento venha de outro lugar que não esse cálculo.

## Ordem de entrega
1. **A garantia** (decisão 6): `last_occurred_at`, a data monotônica e os testes.
2. **Saldo numa data** (decisão 4): porta e serviço de consulta, a busca binária no adaptador de
   persistência, o endpoint e a carência.
3. **Evolução** (decisão 5): `daily-balances` no core e `balance-history` no `app-api`.
4. **Telas:** o saldo do dia no cabeçalho do extrato (desktop, M-Wall e console) e o gráfico da evolução
   (desktop e M-Wall).

## Testes
- **Busca binária contra a busca direta** num ledger gerado com lançamentos em vários dias e instantes
  repetidos, em cortes aleatórios: o mesmo resultado sempre.
- **A virada do dia em Brasília:** lançamentos às 23:59:59,999 e às 00:00:00,000; a conta aberta depois da
  data; a conta sem lançamentos; `DAY_NOT_CLOSED` para hoje e para o futuro, e a carência.
- **Relógio atrasado:** duas gravações na mesma conta com o relógio voltando; a segunda fica com a data da
  primeira, não antes.
- **Isolamento:** a conta de outro tenant responde 404 (RLS, como os outros endpoints de conta).

## Fora do escopo
- **O informe de rendimentos do IR:** o relatório em si (PDF, campos da Receita, envio ao cliente). Esta
  ADR só entrega o saldo em 31/12 que ele usa. A rotina anual percorre as contas pela listagem paginada e
  chama `balance?at=` duas vezes por conta; com 500 mil contas, são cerca de 20 milhões de leituras
  pontuais, uma vez por ano. Se isso pesar, um endpoint em lote pode vir depois.
- **Rendimentos:** a conta de pagamento não rende; não há o que informar além do saldo.
- **Saldo em instantes que não são o fim de um dia:** a busca binária serve para qualquer instante, mas a
  API expõe só datas, que é o que os casos de uso pedem.

## Consequências
- **Positivas:** nenhum dado novo para manter ou conciliar; o saldo de qualquer dia passado sai exato, a
  qualquer momento, inclusive para dias anteriores a esta entrega; o custo não depende do volume do dia.
- **Negativas:** cada consulta faz cerca de 20 leituras em vez de uma; a regra da data monotônica passa a
  ser uma invariante do ledger, que precisa ser preservada por qualquer mudança futura na gravação.
