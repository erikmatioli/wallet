# ADR-013 — Bloqueios e encerramento da conta

**Status:** proposta

## Contexto
A conta já tem um status (`ACTIVE`, `BLOCKED`, `CLOSED`), mas **nada o altera**: não há API, as 8 contas do
banco local estão ativas desde a abertura. Quem opera o tenant precisa, pelo console:

- **bloquear** uma conta por suspeita de fraude, por decisão da operação ou por **ordem judicial**;
- **desbloquear**;
- **cumprir uma ordem judicial**, transferindo dinheiro de uma conta bloqueada;
- **encerrar** a conta, a pedido do cliente ou da instituição.

Os bloqueios têm donos e regras diferentes. O de fraude é pontual: o dinheiro entra e fica retido enquanto a
prevenção a fraude analisa. O judicial só sai com uma ordem do juiz, e o dinheiro só sai da conta para cumprir
a ordem. Os dois podem existir **ao mesmo tempo**: um único campo de status não serve, porque liberar um
apagaria o outro (a fraude encerra a análise e, sem ordem do juiz, a conta judicialmente bloqueada volta a
movimentar).

O encerramento também alimenta um reporte regulatório: o **CCS** (Cadastro de Clientes do SFN, do Banco
Central) quer saber quando cada CPF/CNPJ **começou e terminou** o relacionamento com a instituição. Esse
reporte fica num serviço próprio, o `wallet-ccs` (ADR-001 dele); aqui fica o que o core precisa ter.

### O que o banco local mostrou (11/10/2026, script só de leitura)
| Item | Situação |
|---|---|
| Status da conta | existe, sem nenhuma forma de alterá-lo; ninguém fora do core lê o valor `BLOCKED` |
| Data de encerramento | **não existe** (`account.closed_at`) |
| Histórico (quem, quando, por quê) | **não existe** |
| Mais de uma conta por CPF no mesmo tenant | não há hoje, mas nada impede |
| O mesmo CPF em dois tenants | sim: são duas instituições, dois relacionamentos |

Hoje o `applyDelta` exige `status = 'ACTIVE'` para **qualquer** movimento.

## Decisões

### 1. O status diz se a conta existe; os bloqueios ficam à parte
- **Status da conta:** só `ACTIVE` ou `CLOSED`. `CLOSED` é **terminal**: se o cliente voltar, abre uma conta
  nova (e, no CCS, um relacionamento novo).
- **Bloqueios:** registros próprios na tabela nova `account_block`. Uma conta pode ter vários ativos ao mesmo
  tempo, e cada um é liberado sozinho, por quem pode liberá-lo.

| Tipo | Recebe | Única saída permitida | Quem cria e libera |
|---|---|---|---|
| `FRAUD` | sim | a devolução de Pix enviada (`PIX_RETURN_OUT`, o MED) | `accounts:block` |
| `ADMINISTRATIVE` | não | nenhuma | `accounts:block` |
| `JUDICIAL` | sim | a **transferência judicial** (item 3) | `accounts:judicial`, sempre com o número do processo |

Em qualquer bloqueio, o **estorno de Pix** (`PIX_REFUND`) entra: ele desfaz um Pix que saiu e o SPI recusou, e
recusá-lo deixaria o dinheiro do cliente perdido no tenant.

**Como os bloqueios se combinam:** um movimento passa só se **todos** os bloqueios ativos o permitirem. A
exceção é a transferência judicial: a ordem do juiz prevalece sobre os bloqueios de fraude e administrativo, e
só uma conta encerrada a impede. Na combinação, o recebimento continua valendo só se nenhum bloqueio
`ADMINISTRATIVE` estiver ativo.

O bloqueio judicial bloqueia a **conta inteira**. O bloqueio de **um valor** (o mais comum no SISBAJUD: "até R$
5.000", e o cliente usa o resto) é o próximo passo e cabe no mesmo modelo: o `account_block` ganha um valor
opcional e o disponível passa a ser o saldo menos o que está bloqueado.

### 2. O ledger lê um resumo, não a tabela de bloqueios
O `applyDelta` continua um `UPDATE` só. A conta ganha duas colunas, recalculadas na **mesma transação** em que
um bloqueio é criado ou liberado (com a versão da conta, ADR-003):

| Coluna | Valor |
|---|---|
| `credit_policy` | `ALLOWED` ou `REFUND_ONLY` (há um `ADMINISTRATIVE` ativo) |
| `debit_policy` | `ALLOWED`, `MED_ONLY` (só `FRAUD`), `JUDICIAL_ONLY` (há um `JUDICIAL`) ou `NONE` |

A condição do `UPDATE` passa a ser: a conta está `ACTIVE`, e o tipo da transação cabe na política do seu
sentido. A transferência judicial passa com `JUDICIAL_ONLY` e também com `MED_ONLY` e `NONE`; o MED passa com
`MED_ONLY`. Quando a regra recusa, o motivo é `ACCOUNT_BLOCKED` (novo, mais preciso que `ACCOUNT_NOT_ACTIVE`,
que fica para a conta encerrada).

O `holder-check` (que o wallet-pix usa para aceitar ou recusar um Pix recebido) responde `ACCOUNT_BLOCKED` (AC06
no SPI) só quando `credit_policy = REFUND_ONLY`, e `ACCOUNT_CLOSED` (AC07) para a conta encerrada. Uma conta com
bloqueio de fraude ou judicial **recebe** o Pix.

### 3. A transferência judicial
Um tipo novo de transação, `JUDICIAL_TRANSFER`, feito só pelo console, com o escopo `accounts:judicial`:

- **referência obrigatória:** o bloqueio judicial que ela cumpre, o número do processo e o protocolo da ordem;
- **valor:** até o saldo; uma ordem pode ser cumprida em mais de uma transferência;
- **`Idempotency-Key`**, como todo movimento;
- **destino:**
  - **outra conta do tenant**, quando o juiz manda transferir a um credor que é cliente;
  - **a conta interna "depósitos judiciais a transferir"** (novo `Account.Kind` `JUDICIAL_TRANSIT`, uma por
    tenant), quando o destino é um depósito judicial em outro banco. O core não tem TED: a tesouraria faz a TED
    fora e registra a **baixa** (`JUDICIAL_TRANSIT_OUT`, com o comprovante), que tira o valor da conta de
    trânsito contra a settlement. Com a ADR-011, essa perna vai para a conta de lastro de onde a TED saiu.
- **no extrato do cliente:** "Transferência por ordem judicial", com o número do processo.

Cumprida a ordem, o operador libera o bloqueio judicial, ou o mantém se a ordem pedir.

### 4. Tudo fica registrado, sem apagar nada
- **`account_block`:** tipo, motivo, observação, referência judicial (processo e protocolo), quem criou e
  quando, quem liberou, quando e por quê. Um bloqueio liberado não é apagado: ele ganha a data de liberação.
- **`account_status_event`**, só com `INSERT`: a **abertura** (`NULL → ACTIVE`) e o **encerramento**
  (`ACTIVE → CLOSED`), com uma sequência global (`seq`) que é a ordem em que o `wallet-ccs` lê.
  `account.closed_at` é preenchido no encerramento.
- A tela do console junta os dois numa linha do tempo da conta.

Motivos do bloqueio: `FRAUD_SUSPICION`, `CUSTOMER_REQUEST`, `REGISTRATION_PENDING`, `INSTITUTION_DECISION`,
`COURT_ORDER`, `OTHER`. A observação é obrigatória em `OTHER`; no judicial, o processo é obrigatório.

As duas tabelas têm RLS por tenant, como as outras. A migração cria o evento de abertura das contas que já
existem, com a data de `account.created_at`, e troca o `CHECK` do status para `ACTIVE`/`CLOSED`.

### 5. Encerrar exige a conta vazia e livre
O core recusa o encerramento se:

- o **saldo não for zero** (`ACCOUNT_NOT_EMPTY`);
- houver um **bloqueio judicial ativo** (`JUDICIAL_BLOCK_ACTIVE`): a ordem precisa ser cumprida ou liberada
  antes.

Bloqueios de fraude e administrativos ativos são liberados no próprio encerramento, com o motivo dele. O core
não vê o que está nos outros serviços, então o console confere antes e mostra uma lista de verificação:

| Verificação | Onde |
|---|---|
| Saldo zero | core |
| Nenhum bloqueio judicial ativo | core |
| Nenhum Pix enviado ainda sem resposta do SPI (um estorno ainda pode chegar) | wallet-pix |
| Nenhum agendamento pendente | wallet-scheduler (o console oferece cancelá-los) |

Com saldo, o operador primeiro devolve o dinheiro ao cliente (um Pix ou um saque, pelos fluxos que já
existem) e depois encerra.

### 6. API
Escopos novos, separados de `ledger:write` (quem movimenta dinheiro não é quem bloqueia):

- **`accounts:block`:** bloqueios de fraude e administrativos;
- **`accounts:judicial`:** bloqueios judiciais e a transferência judicial;
- **`accounts:close`:** encerramento;
- **`ccs:read`:** o feed do `wallet-ccs`.

| Método e caminho | Escopo |
|---|---|
| `POST /v1/accounts/{id}/blocks` | `accounts:block` ou `accounts:judicial`, conforme o tipo |
| `POST /v1/accounts/{id}/blocks/{blockId}/release` | o mesmo de quem criou |
| `GET /v1/accounts/{id}/blocks?active=` | `accounts:read` |
| `POST /v1/accounts/{id}/judicial-transfers` | `accounts:judicial` |
| `POST /v1/accounts/{id}/close` | `accounts:close` |
| `GET /v1/accounts/{id}/timeline` | `accounts:read` |
| `GET /v1/ccs/account-events?after={seq}&limit=500` | `ccs:read` |

Exemplos:

```json
POST /v1/accounts/{id}/blocks
{ "type": "JUDICIAL", "reason": "COURT_ORDER", "operator": "maria.souza",
  "court": { "process": "0001234-56.2026.8.26.0100", "protocol": "SISBAJUD 20261011000123" },
  "note": "bloqueio total da conta" }

POST /v1/accounts/{id}/judicial-transfers
{ "blockId": "…", "amount": 1500.00, "destination": { "kind": "JUDICIAL_TRANSIT" },
  "operator": "maria.souza", "note": "transferência para depósito judicial no Banco do Brasil" }
```

`GET /v1/accounts/{id}` passa a trazer `closedAt`, os bloqueios ativos e o que a conta pode fazer agora
(`canReceive`, `canPay`). O feed do CCS traz os eventos de abertura e encerramento depois de `seq`, em ordem,
com o que o CCS precisa e o evento de domínio não leva (o DomainEvent não carrega CPF nem nome): tipo e número
do documento do titular, nome, agência e conta. Ele lê pela sequência, com índice em `(tenant_id, seq)`.

### 7. Quem fez
O token do console é do **tenant**, não de uma pessoa: o console não tem operadores (o mesmo limite da ADR-011).
Até ter, o console pede o nome do operador no formulário e o core grava o que recebeu, ao lado do `client_id` do
token. É um registro **declarado**, não autenticado; quando o console tiver login de operador, o `operator`
passa a vir do token.

### 8. Os outros produtos
- **wallet-pix:** nada muda; o `holder-check` já devolve os motivos de recusa certos.
- **wallet-scheduler:** um agendamento de uma conta bloqueada para pagamento falha com `ACCOUNT_BLOCKED`, que
  ele já trata como `PAYER_ACCOUNT_BLOCKED` (retentável no dia).
- **app (desktop e M-Wall):** o app-api deixa entrar um cliente com a conta bloqueada, mostra um aviso ("Sua
  conta está bloqueada para pagamentos. Procure o atendimento.") e esconde as ações de pagar; a transferência
  judicial aparece no extrato com o processo; com a conta encerrada, o login é recusado com uma mensagem
  própria.
- **console, na tela da conta:** os bloqueios ativos em destaque, os botões **Bloquear por fraude**, **Bloquear
  tudo**, **Bloqueio judicial**, **Transferência judicial**, **Liberar** e **Encerrar**, cada um visível só
  para o escopo que o permite, e a linha do tempo da conta.

## Fora do escopo
- **Bloqueio de um valor** (item 1): o próximo passo.
- **Integração com o SISBAJUD:** as ordens chegam e são cumpridas pelo operador, à mão.
- **Status do cliente** (`Customer.Status`): o bloqueio aqui é por conta. Bloquear um cliente inteiro é
  bloquear todas as contas dele.
- **Login de operador no console** (item 7).

## Consequências
- **Positivas:** fraude, operação e jurídico bloqueiam e liberam cada um o seu, sem desfazer o do outro; a
  conta com bloqueio de fraude ou judicial continua recebendo; a ordem judicial é cumprida pelo próprio ledger,
  com referência ao processo e partidas dobradas; o `wallet-ccs` lê do core, por um feed ordenado, o que precisa.
- **Negativas:** o `applyDelta` passa a decidir pelo tipo da transação e pelo sentido; criar ou liberar um
  bloqueio reescreve as políticas da conta na mesma transação; um tipo de conta interna e dois tipos de transação
  novos; o operador é declarado até o console ter login.
