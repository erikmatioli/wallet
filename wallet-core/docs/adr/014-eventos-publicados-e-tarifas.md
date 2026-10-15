# ADR-014 — Eventos publicados, conta de receitas e cobrança de tarifas

**Status:** proposta

## Contexto
A cesta de tarifas (ADR-001 do `wallet-fees`) cobra dos clientes uma mensalidade pelo pacote contratado e tarifas por
transação, e devolve a tarifa quando a transação é estornada. Ela é um serviço à parte, que reage ao que acontece no
core. Hoje o core não tem como avisar:

| Peça | Situação hoje |
|---|---|
| Eventos de domínio | gravados no `outbox_event` e só **escritos no log** (`LoggingEventPublisher`): nada sai do core |
| Quem recebe as tarifas | não existe: as contas internas são de lastro (dinheiro dos clientes) e o trânsito judicial |
| Movimento de tarifa | não existe tipo de transação para debitar uma tarifa nem para devolvê-la |
| PF ou PJ do pagador de um Pix recebido | o core recebe o CPF/CNPJ completo, mas guarda só **mascarado** (`***7735`): não dá para saber o tipo |
| Bloqueio por tarifa em aberto | os bloqueios da ADR-013 são fraude, administrativo e judicial |

## Decisões

### 1. O core publica os seus eventos
O `OutboxRelay` passa a publicar no SNS (LocalStack em desenvolvimento), num tópico por agregado, como o wallet-pix já
faz: `wallet-core-transaction-events` e `wallet-core-account-events`. Cada consumidor assina com a sua fila e a sua
DLQ. A publicação continua *at-least-once*: o consumidor deduplica pelo `eventId`.

| Evento | Quando | Novo? |
|---|---|---|
| `wallet.transaction.posted.v1` | toda transação | já existe; ganha os campos abaixo |
| `wallet.customer.onboarded.v1` | abertura de conta (traz o tipo de documento) | já existe |
| `wallet.account.blocked.v1` / `wallet.account.block-released.v1` | criação e liberação de um bloqueio | **novos** |
| `wallet.account.closed.v1` | encerramento | **novo** |

O `TransactionPosted` ganha o que um consumidor precisa sem perguntar de volta ao core:
- `relatedTransactionId`: a transação que um estorno desfaz (o saque, no estorno de saque; o Pix, no `PIX_REFUND`) e,
  numa tarifa, a transação que a gerou;
- `reversal`: se a transação desfaz outra (estorno de saque, `PIX_REFUND`, `FEE_REFUND`);
- `counterpartyDocumentType`: num Pix, `CPF` ou `CNPJ` da contraparte. Para isso, `pix_transaction_detail` ganha a
  coluna `counterparty_tax_id_type`, gravada na hora em que o documento completo ainda está em mãos. Só o **tipo**:
  o documento continua mascarado.

Os eventos continuam sem nome nem documento do cliente (o porquê está no `data-model.md`, seção 8).

### 2. A conta de receitas do tenant
Um tipo de conta novo, `TENANT_REVENUE`, uma por tenant, criada no provisionamento e pela migration, como o trânsito
judicial. Não fica negativa. Recebe as tarifas cobradas e devolve as estornadas. É **dinheiro do tenant**, não de
clientes: não entra no lastro (ADR-011). A soma de todas as contas continua zero: a tarifa só muda o dinheiro de dono.

`GET /v1/treasury/revenue` mostra o saldo e o extrato dela (`ledger:audit`).

### 3. `FEE` e `FEE_REFUND`
| Tipo | Débito | Crédito | Referência |
|---|---|---|---|
| `FEE` | conta do cliente | receitas do tenant | a transação que gerou a tarifa, ou o mês da mensalidade |
| `FEE_REFUND` | receitas do tenant | conta do cliente | a `FEE` que ela devolve (uma vez, no valor inteiro) |

- `POST /v1/accounts/{id}/fees` e `POST /v1/fees/{feeTransactionId}/refunds`, com o escopo novo **`fees:write`**,
  que só o wallet-fees tem. A chave de idempotência é derivada da origem (`fee:{transação}:{operação}`,
  `fee:monthly:{conta}:{AAAA-MM}`): a mesma tarifa nunca é cobrada duas vezes, nem com duas instâncias do serviço.
- O corpo leva a descrição que o cliente vê ("Tarifa: Pix enviado", "Mensalidade do pacote Standard — 10/2026"),
  o código da operação e a referência, gravados em `fee_detail` (append-only, como os outros detalhes).
- **Uma tarifa não passa por cima de bloqueio:** o `FEE` só debita uma conta sem bloqueio de fraude, administrativo
  ou judicial. A exceção é o bloqueio de tarifa (item 4), feito justamente para ela.
- O `FEE_REFUND` entra sob qualquer bloqueio, como os outros estornos (ADR-013).

### 4. O bloqueio de tarifa (`FEE_PENDING`)
Um quarto tipo de bloqueio da ADR-013, criado e liberado pelo wallet-fees (`fees:write`), quando uma tarifa não pôde
ser cobrada por falta de saldo:

| Tipo | Recebe | Única saída permitida |
|---|---|---|
| `FEE_PENDING` | sim | a própria tarifa (`FEE`) e a devolução de Pix recebido (`PIX_RETURN_OUT`, o MED, que é obrigação regulatória) |

Na combinação com os outros bloqueios vale a regra da ADR-013: um movimento passa só se todos os bloqueios ativos o
permitirem. A política de débito ganha o valor `FEE_ONLY`, e `AccountPolicy.gateFor` aceita o `FEE` sob ela. No app
e no M-Wall, o aviso é próprio: "Sua conta tem tarifas em aberto. Ao receber um crédito, elas são cobradas e a
conta volta a pagar."

## Fora do escopo
- As regras da cesta (pacotes, preços, franquias, quando cobrar): ficam no wallet-fees.
- Publicar no SNS os eventos que já estão no outbox antes desta mudança: o relay começa pelos pendentes.

## Consequências
- **Positivas:** o core continua sem depender de quem o consome; qualquer produto novo pode reagir às transações por
  uma fila; a tarifa é uma partida dobrada comum, auditável e reversível; a receita do tenant fica separada do
  dinheiro dos clientes.
- **Negativas:** o core passa a depender do SNS para publicar (o outbox segura os eventos enquanto ele estiver fora);
  o `TransactionPosted` cresce; mais um tipo de conta, dois de transação e um de bloqueio.
