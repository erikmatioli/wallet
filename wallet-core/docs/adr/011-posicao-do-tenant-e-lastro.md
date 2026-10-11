# ADR-011 — Contas de lastro e o dinheiro descoberto do tenant

**Status:** aceita e implementada (substitui o rascunho de 10/10/2026, que separava só "com lastro" e "sem lastro")

## Contexto
O saldo que o cliente vê é um registro no ledger. O dinheiro que o lastreia está **fora** dele, em vários
lugares, e cada meio de pagamento usa o seu:

| Onde o dinheiro está | Quem o movimenta |
|---|---|
| Conta PI, no Banco Central | o Pix (liquidação no SPI) |
| Reservas Bancárias ou Conta de Liquidação | TED (STR), e a recomposição das outras contas |
| CCME ou títulos públicos | o lastro regulatório da moeda eletrônica de uma instituição de pagamento |
| Contas de investimento do tenant | a tesouraria, para cobrir o que foi creditado |
| Caixa, ATM | o saque em papel-moeda |
| Carteira de crédito | a concessão e o pagamento de empréstimos |

A **tesouraria** do tenant acompanha esses lugares e move dinheiro entre eles: abastece a Conta PI antes
da noite, porque um Pix sem saldo é rejeitado na hora (Res. BCB 195, art. 36, §4º); recompõe a Conta PI a
partir da Reservas ou da CCME (mensagens LPI0001 a LPI0004 do STR); e cobre, no fim do dia, os créditos
manuais feitos ao longo dele, trazendo dinheiro de uma conta de investimento.

O core não ajuda nisso hoje. Toda perna interna de um movimento de cliente vai para a **conta de
liquidação** (settlement), uma conta só, distribuída em shards (`SettlementRouter`). Ela diz quanto o
tenant deve aos clientes, mas não **em que lugar** esse dinheiro deveria estar, nem quanto dele foi
**criado** por um depósito manual e ainda não foi coberto.

### O que o banco local mostrou (11/10/2026, scripts só de leitura em `docs/analises/`)
Os lançamentos da settlement, classificados pela conta de lastro que cada um usa de verdade:

| demo-tenant | Saldo esperado | Movimentos |
|---|---|---|
| Conta PI (Pix) | R$ 199,10 | 17 |
| **A regularizar** (dinheiro criado por depósito) | **R$ 223,76** | 3 |
| Caixa (saque) | — | 0 |
| **Total = posição = soma dos clientes** | **R$ 422,86** | |

- **O descoberto não baixa.** R$ 223,76 foram criados por depósito em 09/10; não há como registrar a
  cobertura, então eles seguem descobertos.
- **A Conta PI chegou a R$ −17,00 em 09/10.** Um cliente recebeu um depósito e fez um Pix antes de entrar
  qualquer Pix no tenant: o Pix saiu "pago" com dinheiro criado. No mundo real, a Conta PI precisava ter
  sido abastecida antes. É o alerta que a tesouraria precisa ver.
- **O saque tem lastro** (o papel-moeda que o caixa entrega) e **o estorno de Pix não é entrada**: ele
  desfaz um Pix enviado que o SPI recusou, e os dois se anulam na Conta PI.

O objetivo **não é imitar a Conta PI** nem o STR. É dar à tesouraria a visão de onde o dinheiro deveria
estar, de onde ele foi criado sem origem, e um jeito de registrar como ela resolveu.

## Decisões

### 1. Contas de lastro, cadastradas por tenant
Uma conta de lastro é uma conta **interna** do ledger (novo `Account.Kind` `BACKING`) que espelha um lugar
onde o tenant guarda dinheiro. Ela tem:

- **tipo:** `PIX_PI`, `RESERVES` (Reservas ou Conta de Liquidação), `CCME`, `INVESTMENT`, `CASH`,
  `CREDIT_PORTFOLIO` ou `UNASSIGNED` ("a regularizar");
- **nome** e **referência externa** (instituição, agência e conta, ou o identificador da conta no Banco
  Central), para a conciliação futura;
- **shards**, como a settlement hoje: uma conta de lastro movimentada por todos os Pix do tenant não pode
  ser uma linha só disputada por todos eles.

Todo tenant nasce com três: **Conta PI**, **Caixa** e **A regularizar**. As outras (Reservas, CCME,
investimentos) são cadastradas pela tesouraria.

O saldo de uma conta de lastro segue a convenção da settlement (negativo é o que o tenant tem naquele lugar
para os clientes); a API e as telas mostram o sinal invertido, como "saldo esperado".

### 2. Cada movimento de cliente usa a conta de lastro do seu meio
A perna interna deixa de ir para a settlement e vai para a conta de lastro do tipo correspondente:

| Movimento | Conta de lastro |
|---|---|
| Pix recebido, enviado, estorno, devoluções | Conta PI |
| Saque | Caixa |
| **Depósito** | **A regularizar** |
| TED (quando existir) | Reservas |
| Concessão e pagamento de crédito (quando existirem) | Carteira de crédito |

Transferências entre clientes continuam sem perna interna. A soma das contas de lastro é a posição do
tenant, igual à soma dos saldos dos clientes, como a settlement é hoje. O `SettlementRouter` vira um roteador
de lastro: escolhe a conta pelo tipo de movimento e a shard pelo id da transação.

### 3. A movimentação de tesouraria
Um novo tipo de transação, `TREASURY_TRANSFER`, move valor entre contas de lastro. Ela **nunca toca um
cliente**, e a posição do tenant não muda. Dois usos:

- **Cobertura:** "o dinheiro dos créditos de hoje chegou, da conta de investimento X para a Reservas".
  Sai de **A regularizar** e entra na conta de lastro onde o dinheiro está agora. A origem externa (a conta
  de investimento de onde ele veio) e o comprovante vão na referência: o dinheiro próprio do tenant não é um
  saldo de cliente e não está no ledger.
- **Recomposição:** "aportei R$ X na Conta PI vindo da Reservas" (no mundo real, uma LPI0001). Sai de uma
  conta de lastro e entra em outra.

Toda movimentação tem valor, origem, destino, referência (comprovante e observação) e `Idempotency-Key`,
como os outros movimentos. Exige um escopo novo, **`treasury:write`**: quem cria dinheiro (`ledger:write`,
o depósito) não é necessariamente quem o cobre.

`POST /v1/treasury/transfers`

```json
{ "from": "<conta A regularizar>", "to": "<conta Reservas>", "amount": 223.76,
  "reference": { "document": "TED 000123", "note": "resgate do CDB, cobertura dos créditos de 09/10" } }
```

### 4. A visão da tesouraria (API)
Leitura com o escopo `ledger:audit`, o mesmo da auditoria do ledger:

- `GET /v1/treasury/backing-accounts`: cada conta de lastro com o **saldo esperado agora** (a soma das
  shards: uma linha por shard, não uma varredura do ledger) e o do **fim do dia anterior**.
- `GET /v1/treasury/backing-accounts/{id}/daily-balances?from=&to=`: o saldo esperado no fim de cada dia,
  com a busca binária da ADR-012 em cada shard, somada.
- `GET /v1/treasury/backing-accounts/{id}/entries?before=&limit=`: os lançamentos da conta de lastro,
  intercalados entre as shards pela sequência (que segue a data, ADR-012), com o cliente envolvido e, na
  movimentação de tesouraria, a referência. Na conta **A regularizar**, é a lista "onde criei dinheiro e
  como resolvi": cada depósito e cada cobertura.
- `GET /v1/treasury/unassigned/daily?from=&to=`: por dia, quanto foi **criado** (depósitos), **coberto**
  (coberturas) e o **descoberto** no fim do dia.

Uma conta de lastro com saldo esperado negativo (como a Conta PI acima) é sinalizada: o tenant movimentou
ali mais do que tinha.

### 5. A tela de tesouraria no console
Uma entrada nova na barra do console, `/tesouraria`, para quem tem `ledger:audit`:

- **Descoberto agora**, em destaque e na cor de alerta, com o criado e o coberto de hoje.
- Um cartão por conta de lastro: saldo esperado agora, no fim de ontem, e o alerta quando negativo.
- O extrato de cada conta de lastro; o da **A regularizar** mostra cada depósito (cliente, valor,
  descrição) e cada cobertura (referência, valor).
- **Registrar cobertura** e **Registrar recomposição**, visíveis só para quem tem `treasury:write`.
- O descoberto no fim de cada dia, para a tesouraria ver se fechou o dia coberto.

### 6. Os dados que já existem
O ledger é imutável: nada é reescrito. A migração:

1. cria as contas de lastro iniciais de cada tenant (Conta PI, Caixa e A regularizar, com shards);
2. lança, por tenant, uma transação de **reclassificação** por tipo de lastro, levando o saldo da settlement
   para a conta de lastro correspondente (hoje, no demo-tenant: R$ 199,10 para a Conta PI e R$ 223,76 para a
   A regularizar), com uma descrição que diz por quê;
3. deixa a settlement zerada e sem lançamentos novos: ela fica só como histórico.

Depois disso, a soma das contas de lastro continua igual à soma dos clientes, conferida pela auditoria do
ledger.

### 7. Desempenho
Nada aqui varre o ledger:
- o saldo esperado de uma conta de lastro é a soma das suas shards (poucas linhas);
- o saldo no fim de um dia é a busca binária da ADR-012 em cada shard;
- os lançamentos são intercalados pela sequência de cada shard, sem índice novo;
- o criado e o coberto por dia saem do extrato da A regularizar, que só recebe depósitos e coberturas.

## Ajustes feitos na implementação
- **O estorno de um saque vai para o Caixa**, não para A regularizar: ele desfaz o saque onde ele aconteceu e não
  cria dinheiro. Na migração, é reconhecido pela chave de idempotência (`reversal:...`).
- **Cobertura limitada ao descoberto.** Uma cobertura maior que o descoberto é recusada
  (`COVERAGE_EXCEEDS_UNCOVERED`); as shards de A regularizar ficam travadas durante a conferência, então duas
  coberturas ao mesmo tempo não passam juntas. Nada entra em A regularizar (`INVALID_TREASURY_DESTINATION`): ela só
  recebe o que os depósitos criam.
- **A reclassificação tem tipo próprio**, `RECLASSIFICATION`: uma transação por tenant, com várias pernas (zera
  cada shard da settlement e põe o valor na primeira shard de cada conta de lastro). A settlement fica `CLOSED`.
- **O histórico anterior à migração continua na settlement.** O saldo esperado de uma conta de lastro num dia
  anterior à migração é zero; o valor aparece no dia da reclassificação, contado como "criado" em A regularizar.
- **O extrato de uma conta de lastro** usa como cursor a sequência de cada shard (por exemplo `18.1.7`), não a data:
  é exato e usa só o índice que já existe. O saldo esperado depois de cada lançamento é calculado a partir das
  linhas imutáveis (o saldo da shard depois do lançamento mais o da próxima linha de cada outra shard), sem
  depender de um snapshot.
- **Shards:** as três contas iniciais têm o número de shards do provisionamento (o campo virou `backingShards`); as
  cadastradas pela tesouraria têm uma, porque só a tesouraria as movimenta.
- **Tela:** `/tesouraria` no console, com o descoberto em destaque, um cartão por conta de lastro, o extrato da
  selecionada, os formulários de cobertura, recomposição e cadastro (só com `treasury:write`) e o descoberto no fim
  dos últimos 14 dias.

## Fora do escopo (próximos passos)
- **Conciliação com o extrato real.** Comparar o saldo esperado de cada conta de lastro com o extrato de
  verdade (a camt.053 da Conta PI, o extrato bancário da conta de investimento). A referência externa da
  conta de lastro deixa o lugar pronto.
- **O menor saldo dentro do dia.** Saber que a Conta PI ficou negativa às 14h, e não só no fim do dia, exige
  acompanhar o mínimo a cada lançamento. Pode ser uma coluna da shard atualizada no mesmo `UPDATE`, como a
  data na ADR-012.
- **TED e crédito**, que ainda não existem no core: o roteamento já prevê as contas de lastro deles.
- **Integração com o STR ou o SPI** (comandar uma LPI0001 de verdade). A movimentação de tesouraria registra
  o que a tesouraria fez lá fora.
- **Quem fez o depósito e quem cobriu.** O token é do tenant, não de uma pessoa: o console não tem operadores.

## Consequências
- **Positivas:** a tesouraria vê, por lugar, onde o dinheiro deveria estar e o que foi criado sem origem;
  registra coberturas e recomposições no próprio ledger, com partidas dobradas e histórico imutável; o
  descoberto e os saldos de lastro no fim de cada dia saem da mesma busca da ADR-012.
- **Negativas:** um tipo de conta e um tipo de transação novos no domínio; uma migração com transações de
  reclassificação; o roteamento de cada movimento passa a depender do cadastro das contas de lastro do
  tenant.

## Referências
- Resolução BCB nº 195/2022 (Regulamento do SPI e da Conta PI): arts. 19, 22, 36 e 44.
- Resolução BCB nº 80/2021, art. 22: o saldo de moeda eletrônica mantido no Banco Central (CCME) ou em
  títulos públicos federais.
- Roteiro para Participação Direta no SPI e abertura de Conta PI (BCB): as mensagens LPI0001 a LPI0006 do
  STR e as consultas camt.060, camt.052, camt.053 e camt.054.
