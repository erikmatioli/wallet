# ADR-001 — Cesta de tarifas (wallet-fees)

**Status:** proposta

## Contexto
O tenant quer cobrar dos clientes pelos serviços da conta, com a receita indo para uma conta dele:

- cada conta tem um **pacote**: PF começa no `basic` e pode ir para `standard` e `vip`; PJ, no `pj basic`, `pj
  standard` e `pj vip`. Toda conta nasce no básico do seu tipo; o operador migra pelo console;
- o pacote tem uma **mensalidade** e, por **operação** (Pix enviado, Pix recebido, transferência, saque), uma
  **franquia** mensal de operações gratuitas e o **preço** da operação depois dela;
- o **Pix recebido** pode ter preços diferentes conforme o pagador seja PF ou PJ;
- os valores mudam com o tempo;
- a tarifa só é cobrada **com saldo e com a conta desbloqueada**. Sem saldo, a conta é bloqueada para saída e a tarifa
  é cobrada no próximo crédito; com a conta bloqueada, a cobrança espera o desbloqueio;
- o **estorno** de uma transação devolve a tarifa dela; a **devolução** de um Pix não mexe na tarifa paga.

Exemplo, PF `basic`:

| Item | Franquia/mês | Preço depois da franquia |
|---|---|---|
| Mensalidade | — | R$ 0,00 |
| Saque | 0 | R$ 1,00 |
| Transferência entre contas | 2 | R$ 1,00 |
| Pix enviado | 0 | R$ 1,50 |
| Pix recebido de PF / de PJ | configurável | configurável |

O core publica as transações, guarda a receita numa conta do tenant e executa a cobrança (ADR-014 do wallet-core).

## Decisões

### 1. Um produto à parte, em .NET 10
`wallet-fees`, em **C# / .NET 10 (LTS)**, com a mesma arquitetura dos outros produtos:

| Projeto | Papel |
|---|---|
| `Fees.Domain` | pacotes, tabelas de preço, franquia, tarifa e as regras, sem framework |
| `Fees.Application` | casos de uso e portas |
| `Fees.Infrastructure` | Postgres (Npgsql + Dapper, SQL explícito como o `JdbcClient` do core; migrations em SQL com DbUp), SQS, cliente HTTP do core |
| `Fees.Api` | ASP.NET Core: a API, o consumidor da fila e o job da mensalidade (`BackgroundService`) |
| `Fees.Tests` | xUnit, fakes das portas e Testcontainers para Postgres e LocalStack |

Banco próprio (`fees`) no Postgres do core, como os outros produtos; porta 8088; JWT validado pelo JWKS do core;
OpenTelemetry com o mesmo collector. Para chamar o core, usa credenciais do tenant com o escopo `fees:write`, uma
configuração por tenant, como o wallet-scheduler.

### 2. Pacotes e tabelas de preço versionadas
- **Pacote** (`package`): por tenant, com código, nome e tipo de cliente (`PF`/`PJ`). Os seis iniciais são criados na
  primeira vez que o tenant aparece; o operador pode renomear e criar outros depois.
- **Tabela de preço** (`fee_schedule`): pertence a um pacote e vale **a partir de** uma data (`valid_from`, em
  Brasília). Mudar um preço é criar uma tabela nova; a antiga fica, para explicar o que foi cobrado. Uma transação é
  tarifada pela tabela vigente na data dela.
- A tabela tem a **mensalidade** e uma linha por **operação**: franquia mensal e preço depois dela. O Pix recebido tem
  um preço para pagador PF e outro para pagador PJ.

| Operação | Vem de | Conta tarifada |
|---|---|---|
| `PIX_OUT` | `PIX_OUT` | quem envia |
| `PIX_IN` | `PIX_IN` (preço pelo tipo de documento do pagador) | quem recebe |
| `TRANSFER_OUT` | `TRANSFER`, a perna de débito | quem transfere |
| `WITHDRAWAL` | `WITHDRAWAL` | quem saca |

Não são tarifados: depósito, devoluções de Pix, estornos, movimentos de tesouraria e judiciais, e as próprias
tarifas. Uma operação nova é uma linha nova no catálogo, sem mudar o modelo.

### 3. O pacote de cada conta
O `wallet-fees` monta a sua própria visão das contas a partir dos eventos do core (`CustomerOnboarded` traz o tipo de
documento): toda conta nova entra no básico do seu tipo. O operador migra pelo console
(`PUT /v1/fees/accounts/{id}/package`, só para um pacote do mesmo tipo de cliente). Cada migração fica registrada,
com quem fez e quando.

- **Tarifa por transação:** vale o pacote da conta na hora da transação.
- **Franquia:** conta por conta, por operação e por mês de Brasília; numa migração no meio do mês, o que já foi usado
  continua contando e passa a valer a franquia do pacote novo.
- **Mensalidade:** cobrada no dia 1, pelo pacote em vigor naquele dia; uma migração no meio do mês muda a
  mensalidade a partir do mês seguinte, sem proporcional.

### 4. Como uma transação vira tarifa
O consumidor da fila recebe o `TransactionPosted` e:
1. descarta se já processou aquele `eventId`;
2. acha a perna da conta tarifada e a operação (item 2);
3. soma 1 no contador `(conta, mês, operação)`, num `UPDATE ... RETURNING` (duas transações ao mesmo tempo não pegam
   a mesma vaga da franquia);
4. se o contador passou da franquia e o preço é maior que zero, cria a **tarifa** (`fee_charge`, única por transação
   de origem e operação) e tenta cobrá-la (item 5).

Tudo na mesma transação do banco `fees`; a chamada ao core vem depois, e a idempotência dela (`fee:{transação}:{op}`)
torna a repetição segura.

### 5. Cobrança: saldo, bloqueio e a fila de pendentes
Uma tarifa só é cobrada **inteira** (nunca em parte), por um `FEE` no core:

| Resposta do core | O que acontece |
|---|---|
| cobrada | tarifa `CHARGED`, com o id da transação de tarifa |
| `INSUFFICIENT_FUNDS` | tarifa fica `PENDING`; se a conta ainda não tem, o wallet-fees cria o **bloqueio de tarifa** (`FEE_PENDING`): a conta recebe, mas não paga |
| `ACCOUNT_BLOCKED` (fraude, administrativo ou judicial) | tarifa fica `PENDING`, **sem** bloqueio de tarifa: a cobrança espera o desbloqueio |
| `ACCOUNT_NOT_ACTIVE` (encerrada) | tarifa `CANCELLED` |

**Quando as pendentes são cobradas de novo**, da mais antiga para a mais nova, parando na primeira que não couber:
- a cada **crédito** que a conta recebe (um `TransactionPosted` que credita a conta);
- quando um bloqueio é **liberado** (`wallet.account.block-released.v1`);
- numa varredura de hora em hora, por segurança.

Quando não sobra nenhuma pendente, o wallet-fees **libera o bloqueio de tarifa**. Um encerramento de conta libera o
bloqueio de tarifa como os outros não judiciais (ADR-013) e cancela as pendentes.

### 6. Estorno e devolução
- **Estorno** (`reversal = true` no evento, com a transação que ele desfaz): a tarifa daquela transação é
  **devolvida** (`FEE_REFUND`) se foi cobrada, ou **cancelada** se ainda estava pendente; a vaga da franquia volta
  para o mês da transação estornada.
- **Devolução de Pix** (`PIX_RETURN_IN`/`PIX_RETURN_OUT`): não mexe na tarifa nem na franquia.

### 7. Mensalidade
Um job no dia 1, às 00:05 de Brasília, cria a mensalidade de cada conta ativa cujo pacote tem mensalidade maior que
zero (`fee:monthly:{conta}:{AAAA-MM}`), e ela segue a mesma cobrança do item 5. Se o serviço estiver fora no dia 1,
o job cobra os meses que faltaram quando voltar.

### 8. API e escopos
Escopos novos no core: **`fees:read`** e **`fees:admin`** (operador), além do `fees:write` (o próprio wallet-fees).

| Método e caminho | Escopo |
|---|---|
| `GET /v1/fees/packages` | `fees:read` |
| `GET /v1/fees/packages/{id}/schedules` | `fees:read` |
| `POST /v1/fees/packages/{id}/schedules` (nova tabela, com `validFrom`) | `fees:admin` |
| `GET /v1/fees/accounts/{id}` (pacote, uso da franquia no mês, tarifas e pendências) | `fees:read` |
| `PUT /v1/fees/accounts/{id}/package` | `fees:admin` |

### 9. Os outros produtos
- **Console:** uma tela de **tarifas** (pacotes e tabelas de preço, com a data de vigência) e, na tela da conta, uma
  seção **Pacote e tarifas** (pacote atual e migração, franquia usada no mês, tarifas cobradas e pendentes). A rota
  `/v1/fees` vai para o wallet-fees pelo proxy, como `/v1/schedules` vai para o scheduler.
- **App e M-Wall:** a tarifa aparece no extrato ("Tarifa: Pix enviado"); com o bloqueio de tarifa, o aviso
  "Sua conta tem tarifas em aberto. Ao receber um crédito, elas são cobradas e a conta volta a pagar."
- **wallet-pix, scheduler:** nada muda. Um Pix ou agendamento de uma conta com bloqueio de tarifa é recusado como
  qualquer outro bloqueio.

### 10. Ordem de entrega
1. **Core (ADR-014):** publicação dos eventos no SNS, conta de receitas, `FEE`/`FEE_REFUND`, bloqueio `FEE_PENDING`,
   o tipo de documento do pagador do Pix, escopos novos.
2. **wallet-fees, base:** projeto .NET, banco, pacotes e tabelas de preço, API de configuração, projeção das contas.
3. **Tarifa por transação:** consumidor da fila, franquia e cobrança, com pendências e o bloqueio de tarifa.
4. **Estorno e mensalidade.**
5. **Console e app.**

## Fora do escopo
- Isenções e descontos por cliente, tarifas por faixa de valor, cobrança proporcional na migração de pacote.
- Nota fiscal e contabilização da receita do tenant.
- Tarifas de produtos que ainda não existem (TED, boleto, cartão): entram como operações novas no catálogo.

## Consequências
- **Positivas:** o core não sabe nada de pacote nem de preço e não espera o wallet-fees para mover dinheiro; mudar
  preço não exige deploy e não reescreve o passado; tarifa e estorno de tarifa são partidas dobradas comuns; o
  bloqueio de tarifa reaproveita o modelo de bloqueios da ADR-013.
- **Negativas:** a tarifa é cobrada alguns segundos depois da transação (e pode ficar pendente); uma quarta linguagem
  no monorepo (CI, imagem e convenções de .NET); o wallet-fees precisa de credenciais de cada tenant para cobrar.
