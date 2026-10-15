# wallet-fees — cesta de tarifas

Pacotes de tarifas por conta: mensalidade e preço por operação, com franquia mensal, para pessoas (PF) e empresas
(PJ). Decisões: [ADR-001](docs/adr/001-cesta-de-tarifas.md). No core: [ADR-014 do wallet-core](../wallet-core/docs/adr/014-eventos-publicados-e-tarifas.md)
(eventos publicados, conta de receitas do tenant, `FEE`/`FEE_REFUND` e o bloqueio de tarifa).

| | |
|---|---|
| Stack | C# / .NET 10, ASP.NET Core (minimal APIs), Npgsql + Dapper, DbUp, AWS SDK (SQS) |
| Banco | `fees`, no Postgres do wallet-core (papéis `fees_owner` para as migrations e `fees_app` em tempo de execução) |
| Porta | 8088 |
| Entra por | JWT do wallet-core (`fees:read`, `fees:admin`), validado pelo JWKS do core |
| Chama | o core, com as credenciais de cada tenant (`accounts:read fees:write`): cobrança, bloqueio de tarifa, contas |
| Ouve | a fila `wallet-fees-core-events`, nos tópicos do core (`wallet-core-transaction-events`, `wallet-core-account-events`) |

## O que já faz (etapas 2 e 3 da ADR-001)

- **Pacotes:** cada tenant começa com seis (`PF_BASIC`, `PF_STANDARD`, `PF_VIP`, `PJ_BASIC`, `PJ_STANDARD`,
  `PJ_VIP`), todos com a tabela do exemplo da ADR (sem mensalidade; saque R$ 1,00; duas transferências grátis e
  depois R$ 1,00; Pix enviado R$ 1,50; Pix recebido grátis).
- **Tabelas de preço com vigência:** um preço novo vale a partir de uma data (hoje ou depois, em Brasília); as
  antigas ficam. Uma tabela que ainda não entrou em vigor pode ser trocada por outra da mesma data.
- **Contas:** toda conta nova (evento `wallet.customer.onboarded.v1`) entra no básico do seu tipo; as que já
  existiam são trazidas do core na subida. O operador migra a conta para outro pacote do mesmo tipo, e cada
  migração fica registrada. Contas encerradas (`wallet.account.closed.v1`) são marcadas.

- **Tarifa por transação:** cada `wallet.transaction.posted.v1` tarifável (Pix enviado e recebido, transferência,
  saque) conta na franquia do mês (Brasília) da conta; passada a franquia, vira uma tarifa pelo preço da tabela em
  vigor no dia da transação, cobrada no core (`POST /v1/accounts/{id}/fees`, idempotente por `fee:{transação}:{op}`).
- **Pendências:** sem saldo, a tarifa fica pendente e a conta recebe o bloqueio de tarifa (`FEE_PENDING`); com outro
  bloqueio, só espera. As pendentes são cobradas da mais antiga para a mais nova, inteiras, a cada crédito, a cada
  bloqueio liberado e numa varredura de hora em hora; sem nenhuma pendente, o bloqueio de tarifa é liberado. Conta
  encerrada cancela as pendentes.

Ainda não: o estorno da tarifa e a mensalidade (etapa 4).

## API

| Método e caminho | Escopo | O quê |
|---|---|---|
| `GET /v1/fees/packages` | `fees:read` | Os pacotes, com a tabela em vigor e a próxima |
| `GET /v1/fees/packages/{id}/schedules` | `fees:read` | Todas as tabelas do pacote |
| `POST /v1/fees/packages/{id}/schedules` | `fees:admin` | Uma tabela nova a partir de `validFrom` |
| `GET /v1/fees/accounts/{id}` | `fees:read` | O pacote da conta, as migrações e, em `fees`, a franquia usada no mês, o total pendente e as últimas tarifas |
| `PUT /v1/fees/accounts/{id}/package` | `fees:admin` | Migra a conta para outro pacote do mesmo tipo |
| `GET /health` | — | |

Valores em reais com duas casas, como na API do core. Uma tabela:

```json
POST /v1/fees/packages/{id}/schedules
{
  "validFrom": "2026-11-01",
  "monthlyFee": 9.90,
  "operator": "maria.souza",
  "operations": [
    { "operation": "PIX_OUT", "freePerMonth": 5, "price": 1.20 },
    { "operation": "PIX_IN", "freePerMonth": 0, "price": 0.00, "priceFromCompany": 0.50 },
    { "operation": "TRANSFER_OUT", "freePerMonth": 2, "price": 1.00 },
    { "operation": "WITHDRAWAL", "freePerMonth": 0, "price": 1.00 }
  ]
}
```

Uma operação que a tabela não lista é gratuita. `priceFromCompany` só existe no Pix recebido: o preço quando quem
pagou é uma empresa.

Erros em `application/problem+json` com um `code` estável: `PAST_DATE`, `SCHEDULE_IN_FORCE`, `INVALID_PRICE`,
`PACKAGE_OF_ANOTHER_TYPE`, `SAME_PACKAGE`, `ACCOUNT_CLOSED`, `PACKAGE_NOT_FOUND`, `ACCOUNT_NOT_FOUND`…

## Rodando

Na stack inteira: `scripts/dev-stack.sh up`, na raiz do repositório. Sozinho, depois do core e do wallet-pix
(cujo LocalStack tem a fila):

```bash
docker compose up -d --build     # http://localhost:8088
```

### Build e testes

Com o SDK do .NET 10 instalado:

```bash
dotnet test WalletFees.slnx
```

Sem o SDK, pelo container (os testes de Postgres usam o Docker do host, pelo Testcontainers):

```bash
docker run --rm -v "$PWD:/src" -w /src -v /var/run/docker.sock:/var/run/docker.sock \
  -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal mcr.microsoft.com/dotnet/sdk:10.0 dotnet test WalletFees.slnx
```

## Como está organizado

| Projeto | Papel |
|---|---|
| `src/Fees.Domain` | Pacotes, tabelas de preço, contas e as regras, sem framework |
| `src/Fees.Application` | Casos de uso (`PackageService`, `AccountService`, `ChargeService`) e as portas |
| `src/Fees.Infrastructure` | Postgres (repositórios com Dapper, migrations em `Migrations/*.sql` com DbUp), o consumidor da fila, a varredura das pendentes e o cliente do core |
| `src/Fees.Api` | ASP.NET Core: a API, a validação do JWT e o tratamento de erros |
| `tests/Fees.Tests` | Domínio, casos de uso com fakes em memória e Postgres de verdade (Testcontainers) |

Cada requisição e cada mensagem da fila têm a sua conexão e a sua transação (`DbSession`, por escopo); um caso de
uso que chama outro entra na transação dele. Toda tabela tem `tenant_id`, e o tenant vem sempre do token.
