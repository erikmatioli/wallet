# ADR-011 — Posição do tenant e o dinheiro sem lastro

**Status:** proposta

## Contexto
Todo dinheiro que entra ou sai de um tenant passa pela sua **conta de liquidação** (settlement): num
depósito ou num Pix recebido, a conta do cliente é creditada e a de liquidação é debitada; num saque ou
num Pix enviado, o contrário. Transferências entre clientes não passam por ela. São várias contas
(*shards*, quatro hoje) para não haver uma linha disputada por todas as transações do tenant
(`SettlementRouter`), e o saldo de cada uma sozinha não significa nada.

Nada disso aparece na API: a listagem de contas só traz contas de cliente, e o extrato pagina por um
cursor que vale para uma conta só. O operador não tem como saber **quanto dinheiro o tenant tem**.

Uma consulta no banco local (10/10/2026) mostrou que os dados já respondem, e que a pergunta que importa
é outra: **de onde veio esse dinheiro**.

| | demo-tenant |
|---|---|
| Posição (= soma dos saldos dos clientes) | R$ 395,76 |
| Pix recebido (lastro: liquidação no SPI) | R$ 251,22 |
| Pix enviado, líquido dos estornos | − R$ 79,22 |
| **Com lastro** | **R$ 172,00** |
| Depósitos manuais | R$ 223,76 |
| Saques | R$ 0,00 |
| **Sem lastro** | **R$ 223,76 (56,5% da posição)** |

- **O Pix tem lastro.** Cada Pix recebido corresponde a uma liquidação no SPI, com EndToEndId e a conta
  de origem gravados (ADR-010). O Pix enviado também, do outro lado.
- **O depósito não tem.** `POST /v1/accounts/{id}/deposits` cria dinheiro no ledger com o que o operador
  digitou. Depois da ADR-010, o Pix não usa mais esse endpoint: todo `DEPOSIT` hoje é um crédito manual.
  O ledger guarda data, valor, cliente, uma descrição livre (às vezes vazia) e a chave de idempotência.
  Não guarda **qual é o lastro** (uma TED, um boleto, dinheiro em espécie) nem **quem** creditou.
- **O estorno não é entrada.** Um Pix recusado pelo SPI (AC03, por exemplo) debita o cliente, credita a
  liquidação e, segundos depois, o estorno faz o caminho inverso (`related_transaction_id` liga os dois).
  O dinheiro nunca saiu da instituição. Contar o estorno como entrada infla as entradas, e contar o Pix
  como saída infla as saídas.

## Decisões

### 1. A posição do tenant pela origem do dinheiro
Um endpoint novo responde quanto o tenant tem e de onde veio:

`GET /v1/settlement/position` (escopo `ledger:audit`, o mesmo da auditoria do ledger)

```json
{
  "position": 395.76,
  "customersBalance": 395.76,
  "reconciled": true,
  "backed":   { "pixIn": 251.22, "pixReturnIn": 0.00, "pixOut": 100.21, "pixRefund": 20.99, "pixReturnOut": 0.00, "net": 172.00 },
  "unbacked": { "deposits": 223.76, "withdrawals": 0.00, "net": 223.76, "share": 0.565 },
  "shards": 4,
  "currency": "BRL"
}
```

- **`position`** é a soma das contas de liquidação com o sinal invertido: o dinheiro que o tenant deve
  aos clientes. **`reconciled`** confere que ela é igual à soma dos saldos dos clientes. As duas vêm do
  mesmo banco, então uma diferença aponta um bug no ledger, não uma divergência de negócio.
- **Com lastro** (`backed`): Pix recebidos e devoluções recebidas menos Pix enviados e devoluções
  enviadas, com os estornos devolvendo o que foi enviado sem concluir.
- **Sem lastro** (`unbacked`): depósitos menos saques. O saque é tratado como a saída do dinheiro sem
  lastro: o tenant entrega em espécie o que creditou à mão.
- As shards não aparecem uma a uma, só o número delas: o saldo de uma shard não diz nada.

### 2. Entradas e saídas, separadas
`GET /v1/settlement/entries?direction=IN|OUT&origin=SPI|MANUAL&from=&to=&before=&limit=` (mesmo escopo)

Os lançamentos das contas de liquidação, do ponto de vista do tenant e intercalados entre as shards,
mais recentes primeiro:

| Direção | Origem | Tipos |
|---|---|---|
| Entrada | SPI (com lastro) | `PIX_IN`, `PIX_RETURN_IN` |
| Entrada | Manual (sem lastro) | `DEPOSIT` |
| Saída | SPI | `PIX_OUT`, `PIX_RETURN_OUT` |
| Saída | Manual | `WITHDRAWAL` |

- **O estorno não é uma linha própria.** Ele aparece **no Pix enviado que desfez**: a linha do
  `PIX_OUT` traz `refunded: true`, a data e o motivo (`reasonCode`). Os totais de saída não contam esse
  Pix. Um extrato bruto, com o par "saiu e voltou", continua disponível no extrato de cada conta.
- Cada linha traz o **cliente** (nome e conta) do outro lado do lançamento, a descrição e, no Pix, o
  EndToEndId e a contraparte externa (ADR-010).
- **Cursor** opaco (`occurredAt` + id do lançamento), porque a sequência (`sequence_no`) é de cada shard
  e não ordena entre elas.

### 3. Uma tela no console: "Posição do tenant"
Uma entrada na barra do console (`/posicao`), para quem tem `ledger:audit`:

- **Posição** em destaque, com a conciliação ("igual à soma dos clientes").
- Uma **barra com a origem**: com lastro (SPI) e **sem lastro (depósitos)**, com o percentual. O sem
  lastro tem a cor de alerta da tela: é o ponto que pede atenção.
- Duas abas, **Entradas** e **Saídas**, com filtro por origem e por período. Nas entradas, os depósitos
  ficam marcados "sem lastro". Nas saídas, o Pix estornado aparece riscado, com o motivo, e fora do total.
- O nome do cliente leva à tela da conta dele, onde o extrato completo já existe.

### 4. Desempenho
A posição soma todo o ledger das contas de liquidação, que cresce com cada transação do tenant. Para o
volume de hoje (e de um ambiente de desenvolvimento) uma consulta agregada basta. Antes de um volume
real, a soma por tipo vira um contador mantido na mesma transação do lançamento (uma linha por shard e
tipo, atualizada pelo `post`) ou um retrato diário. A API não muda.

O extrato precisa de um índice novo, `ledger_entry (account_id, occurred_at DESC, id DESC)`: o índice
de hoje é por `(account_id, sequence_no)`.

## Fora do escopo (próximos passos)
- **Registrar o lastro do depósito.** O depósito passaria a aceitar a referência do lastro (tipo: TED,
  boleto, espécie, ajuste; número do comprovante; observação), opcional no início e obrigatória por
  configuração do tenant. A tela mostraria quanto do "sem lastro" tem comprovante.
- **Quem fez o depósito.** O token é do tenant, não de uma pessoa: o console não tem operadores. Saber
  quem creditou exige login de operador, uma decisão maior.
- **Atribuir o dinheiro sem lastro a cada cliente.** Dinheiro é fungível: depois de um depósito e de um
  Pix enviado, não dá para dizer qual dos dois saiu. A tela lista os depósitos por cliente, mas a
  divisão com/sem lastro é só do tenant.
- Alertas (por exemplo, sem lastro acima de um percentual) e exportação.

## Consequências
- **Positivas:** o operador passa a ver quanto dinheiro o tenant tem e quanto dele não tem lastro; a
  conciliação ledger × clientes fica visível; os dados já existem, nada muda no que é gravado.
- **Negativas:** dois endpoints e uma consulta agregada que precisará de um contador quando o volume
  crescer; a regra "estorno desfaz o Pix enviado" fica em dois lugares (o ledger e esta visão).
