# ADR-010 — Tipos de transação Pix e tabela de detalhe no wallet-core

**Status:** proposta — substitui em parte a decisão 1 da ADR-001 do wallet-pix ("o core sem a
palavra Pix no domínio")

## Contexto
O wallet-pix movimenta dinheiro pela API genérica do wallet-core, e o ledger só conhece
`DEPOSIT`, `WITHDRAWAL` e `TRANSFER`:

| Evento Pix | Chamada no core hoje | Tipo gravado |
|---|---|---|
| Pix recebido (pacs.008 de entrada) | `POST /v1/accounts/{id}/deposits` | `DEPOSIT` |
| Pix enviado | `POST /v1/accounts/{id}/withdrawals` | `WITHDRAWAL` |
| Estorno (o SPI rejeitou o envio, pacs.002 `RJCT`) | `POST /v1/transactions/{id}/reversals` | `DEPOSIT` |
| Devolução recebida (pacs.004 de entrada) | `POST /v1/accounts/{id}/deposits` | `DEPOSIT` |

Consequências:
- No extrato, um Pix é indistinguível de um depósito ou saque comum.
- A contraparte do Pix (nome, instituição, conta) não existe no core: está só no texto da
  descrição e no banco `pix`. O extrato só mostra contraparte em `TRANSFER`.
- Não há como extrair apenas as transações Pix (exigência regulatória) sem buscar por texto.
- O crédito de um Pix recebido usa `/deposits`, que exige `ledger:write`: o token do Pix
  recebido pode creditar qualquer valor como "depósito", sem nenhuma regra própria do Pix.

## Decisões

### 1. Tipos de transação próprios do Pix
O `TransactionType` ganha cinco valores. O tipo diz **o que aconteceu**; o core deriva dele o
movimento no ledger (sempre a conta do cliente contra a conta de liquidação do tenant, como hoje)
e as regras de integridade.

| Tipo | O que é | Movimento na conta do cliente | Ligado a |
|---|---|---|---|
| `PIX_IN` | Pix recebido | crédito | — |
| `PIX_OUT` | Pix enviado | débito | — |
| `PIX_REFUND` | Estorno: o SPI rejeitou o envio, nenhum Pix foi liquidado | crédito, valor integral | `PIX_OUT` |
| `PIX_RETURN_IN` | Devolução recebida (pacs.004 de entrada) | crédito, total ou parcial | `PIX_OUT` |
| `PIX_RETURN_OUT` | Devolução enviada (pacs.004 de saída) | débito, total ou parcial | `PIX_IN` |

Os nomes seguem o vocabulário que o wallet-pix já usa: `REFUNDED` para estorno e `RETURNED`
para devolução (`PixPayment`). "Estorno" e "devolução" ficam separados porque são fatos
diferentes para o cliente e para o regulatório: no estorno o Pix nunca existiu; na devolução ele
foi liquidado e voltou.

`DEPOSIT`, `WITHDRAWAL` e `TRANSFER` continuam como estão, e os endpoints atuais não mudam.

### 2. Regras de integridade garantidas pelo core
- `PIX_REFUND`: só sobre um `PIX_OUT` da mesma conta, uma única vez, pelo valor integral (a
  mesma garantia que o estorno de saque já tem, com chave derivada da transação original).
- `PIX_RETURN_IN`: só sobre um `PIX_OUT`; a soma das devoluções não passa do valor original.
  Isso já permite várias devoluções parciais, hoje recusadas pelo wallet-pix.
- `PIX_RETURN_OUT`: só sobre um `PIX_IN`; a soma das devoluções não passa do valor original, e
  exige saldo como qualquer débito.
- `PIX_REFUND` e `PIX_RETURN_IN` não convivem sobre o mesmo `PIX_OUT`: se houve estorno, o Pix
  não foi liquidado e não pode ser devolvido.
- Toda transação `PIX_*` tem exatamente uma linha de detalhe (decisão 3).

### 3. Tabela `pix_transaction_detail`
Uma linha por transação Pix, gravada **na mesma transação de banco** que a
`financial_transaction` e os `ledger_entry`. Sem isso volta o problema de um Pix sem
contraparte.

| Coluna | Conteúdo |
|---|---|
| `transaction_id` | PK e FK para `financial_transaction` |
| `tenant_id` | Para a RLS, como nas demais tabelas |
| `end_to_end_id` | EndToEndId do Pix; liga ao registro no wallet-pix |
| `return_id` | Id da devolução (`RtrId`), só em `PIX_RETURN_*` |
| `related_transaction_id` | Transação Pix original, em `PIX_REFUND` e `PIX_RETURN_*` |
| `counterparty_name` | Nome da contraparte |
| `counterparty_tax_id_masked` | CPF/CNPJ **mascarado**, no mesmo formato do `TaxId.masked()` do core (ex.: `***7735`) |
| `counterparty_ispb` | ISPB da instituição da contraparte |
| `counterparty_branch`, `counterparty_account`, `counterparty_account_type` | Conta da contraparte |
| `reason_code` | Motivo do estorno ou da devolução (ex.: `AC03`, `MD06`) |
| `remittance_info` | Texto informado pelo pagador |

- Colunas tipadas, não `jsonb`: consulta regulatória precisa de filtro e índice confiáveis.
- O detalhe é uma cópia fixa do momento do lançamento. O extrato fica estático e não depende do
  wallet-pix estar no ar.
- O CPF/CNPJ completo continua só no banco `pix`. O core guarda apenas a forma mascarada, que é
  o que o extrato mostra.
- Índices: único em `(tenant_id, end_to_end_id, return_id)`, e em `related_transaction_id`
  para as regras da decisão 2.

### 4. API: endpoints próprios, escopos próprios
Os tipos Pix não entram pelos endpoints genéricos: só por endpoints que exigem o detalhe e
aplicam as regras da decisão 2.

| Endpoint | Tipos | Escopo |
|---|---|---|
| `POST /v1/accounts/{id}/pix-credits` | `PIX_IN`, `PIX_RETURN_IN` | `pix:receive` (novo) |
| `POST /v1/accounts/{id}/pix-debits` | `PIX_OUT`, `PIX_RETURN_OUT` | `pix:send` |
| `POST /v1/transactions/{id}/reversals` | `PIX_REFUND` quando a original é `PIX_OUT` | `pix:send` (já existe) |

- Corpo dos dois primeiros: `type`, `amount`, `endToEndId`, `returnId?`,
  `relatedTransactionId?`, `counterparty{...}`, `reasonCode?`, `remittanceInfo?`, com
  `Idempotency-Key` como hoje.
- O endpoint de estorno passa a aceitar `PIX_OUT` além de `WITHDRAWAL`, e copia o detalhe da
  original para o `PIX_REFUND`, ligando as duas.
- O `pix:receive` tira o crédito do Pix recebido do `ledger:write`. Um token de Pix vazado só
  consegue gravar créditos Pix com detalhe e regras, e não depósitos livres.

### 5. Extrato
- Cada entrada ganha um bloco `pix` opcional (`endToEndId`, contraparte, `reasonCode`,
  transação relacionada), preenchido por join com `pix_transaction_detail`.
- Novo filtro `GET /v1/accounts/{id}/statement?types=PIX_IN,PIX_OUT,...`, e o atalho
  `?product=PIX` para todos os `PIX_*`, que atende à extração regulatória.

### 6. Dados antigos
Não há preenchimento retroativo na migration: os Pix já gravados continuam como `DEPOSIT` e
`WITHDRAWAL`. Se for preciso, um script avulso pode reclassificá-los, pois o banco `pix` guarda o
id de cada transação do core (`debit_transaction_id`, `wallet_transaction_id`) e todos os dados
da contraparte.

## Ajustes feitos na implementação

- **EndToEndId do `PIX_OUT` fora da idempotência.** O wallet-pix debita antes de gerar o Pix e
  cria um EndToEndId novo a cada tentativa. Se ele fizesse parte da impressão digital, o retry
  depois de uma queda viraria `IDEMPOTENCY_KEY_REUSED`. Por isso, só no `PIX_OUT`, o EndToEndId
  não entra na impressão digital (a contraparte entra). A resposta de todo lançamento Pix traz o
  `endToEndId` gravado, e num replay é o da primeira tentativa: o wallet-pix usa esse no pacs.008.
- **CPF/CNPJ da contraparte: só o formato é validado** (11 ou 14 caracteres), não os dígitos
  verificadores. O documento vem do SPI ou de dados que o wallet-pix já validou, e recusar o
  crédito de um Pix já aceito por causa de um dado só exibido seria pior.
- **Duplicidade por Pix:** além da `Idempotency-Key`, o índice único
  `(tenant, tipo, EndToEndId, return_id)` impede lançar o mesmo Pix duas vezes com chaves
  diferentes (`409 PIX_ALREADY_POSTED`). O tipo entra na chave porque um Pix entre duas contas do
  mesmo tenant gera um `PIX_OUT` e um `PIX_IN` com o mesmo EndToEndId.
- **Concorrência:** as regras de estorno e devolução rodam sob um advisory lock da transação
  original (`pg_advisory_xact_lock`), já que as tabelas append-only não dão `UPDATE` ao papel da
  aplicação para um `SELECT ... FOR UPDATE`.
- **Defesa no banco:** um constraint trigger adiado recusa, no COMMIT, qualquer transação `PIX_*`
  sem linha em `pix_transaction_detail`, e a tabela é append-only como o ledger.

### O que ficou para depois
- **Várias devoluções parciais no wallet-pix.** O core já soma e limita as devoluções, mas a
  máquina de estados do `PixPayment` ainda vai para `RETURNED` na primeira; a segunda continua
  sem crédito. Mudar isso exige guardar as devoluções no banco `pix`.
- **Tirar `pix:send` de `/withdrawals`.** O wallet-pix não usa mais o saque genérico, mas o
  escopo continua aceito ali para não quebrar uma versão antiga do wallet-pix durante a entrega.
- **Reclassificação dos Pix antigos** (decisão 6), opcional.

## Plano por módulo

### wallet-core

**wallet-domain**
- `TransactionType`: adicionar os cinco tipos.
- `LedgerTransaction`: fábricas `pixCredit` e `pixDebit` que recebem o tipo e montam as pernas
  contra a conta de liquidação, e a validação de que tipo e direção combinam.
- Novos records `PixDetail` e `PixCounterparty`, com o mascaramento do CPF/CNPJ no próprio tipo.
- `TransactionPosted`: passa a carregar os tipos novos. Verificar os consumidores do evento.

**wallet-application**
- `MoveMoneyUseCase`: comandos `PixCreditCommand` e `PixDebitCommand`, e
  `reverseWithdrawal` generalizado para aceitar `PIX_OUT` e gerar `PIX_REFUND`.
- `MoveMoneyService`: regras da decisão 2, com a mesma idempotência e o mesmo fingerprint dos
  comandos atuais.
- Nova porta de saída para gravar e ler o detalhe, ou extensão do `TransactionJournal`.
- `QueryAccountUseCase`: `StatementEntry` com o detalhe opcional e o filtro por tipos.
- Testes em `MoveMoneyServiceTest` e `InMemoryFixture` para cada regra.

**wallet-adapter-out-persistence**
- Migration `V4__pix_transaction_types.sql`:
  - ampliar o `CHECK` de `type` em `financial_transaction` **e** em `ledger_entry`;
  - criar `pix_transaction_detail` com a mesma política `tenant_isolation` de RLS das demais
    tabelas, e os índices da decisão 3;
  - conceder `pix:receive` aos tenants existentes, como a V3 fez com `pix:send`.
- `JdbcTransactionJournal`: gravar o detalhe na mesma transação.
- `JdbcLedgerRepository`: join com o detalhe no extrato, filtro por tipos, e consultas de soma
  de devoluções por `related_transaction_id`.

**wallet-adapter-in-rest**
- Novo `PixTransactionController` com os dois endpoints.
- `ReversalController`: aceitar `PIX_OUT`.
- `ApiModels`: requests novos, bloco `pix` no `EntryResponse`, parâmetros do extrato, OpenAPI.
- `SecurityConfig`: regras da decisão 4.

**wallet-bootstrap**
- `Tenant.DEFAULT_SCOPES` com `pix:receive`.
- Teste de integração: isolamento por RLS na tabela nova, gravação atômica (falha no detalhe
  desfaz o lançamento), estorno único e soma de devoluções sob concorrência.

**docs**
- `data-model.md` com a tabela nova e os tipos.

### wallet-pix
- `WalletCoreClient`: métodos `pixCredit` e `pixDebit` nos endpoints novos; token com
  `pix:receive` para créditos.
- `ReceivePixService`: crédito como `PIX_IN`, com o pagador como contraparte.
- `SendPixService`:
  - débito como `PIX_OUT`, com o recebedor como contraparte;
  - estorno pelo endpoint de reversal, que passa a gerar `PIX_REFUND`;
  - devolução recebida como `PIX_RETURN_IN`, com `rtrId`, `reasonCode` e a transação original.
- Retirar a limitação "uma devolução por Pix" quando o core passar a controlar a soma.
- Atualizar testes, coleção do Postman, `docs/guia-testes-pix.md` e o README.
- ADR-001 do wallet-pix: anotar que a decisão 1 foi substituída em parte por esta ADR.

### wallet-console
- Extrato: rótulo legível por tipo (Pix recebido, Pix enviado, Estorno de Pix, Devolução
  recebida, Devolução enviada) e contraparte vinda do bloco `pix`.
- Filtro "Somente Pix" usando `?product=PIX`.
- Detalhe da transação com `endToEndId` e motivo, quando houver.

## Ordem de entrega
1. **wallet-core**: tipos, tabela, endpoints e extrato. Compatível com o que existe: os
   endpoints antigos continuam funcionando, então o wallet-pix atual não quebra. Versão minor.
2. **wallet-pix**: passa a usar os endpoints novos. Daqui em diante todo Pix novo sai com tipo e
   detalhe.
3. **wallet-console**: extrato com contraparte e filtro.
4. **Opcional**: script de reclassificação dos Pix antigos (decisão 6).

`PIX_RETURN_OUT` entra no core na etapa 1, mas só será usado quando o wallet-pix implementar a
devolução de saída, que continua fora do escopo.

## Consequências
- **Positivas:** extrato com contraparte e sem consulta externa; extração só de Pix por filtro
  simples; integridade de estorno e devolução garantida no core, e não só no wallet-pix; token de
  Pix recebido sem acesso a depósitos livres.
- **Negativas:** o core passa a conhecer o Pix. Cada novo produto (TED, boleto) vai exigir tipos,
  migration e regras no core. É um custo aceito: o Pix é um produto central da carteira, e a
  integridade da transação completa é responsabilidade do core.
- **Neutras:** dados de contraparte duplicados entre o core (forma mascarada) e o banco `pix`
  (forma completa), cada um para o seu uso.
