# wallet-scheduler

Agenda transferências e Pix para uma data futura e executa no dia, com retentativa e motivo
legível de cada falha. Escrito em **Kotlin**, com a mesma arquitetura hexagonal dos outros
projetos. As decisões estão na [ADR-001](docs/adr/001-servico-de-agendamento.md).

Quem usa: o **wallet-console** (seção de agendamentos da conta) e o **wallet-app** (o `app-api`
chama como o tenant do app).

## Stack
- Kotlin 2.3 (versão gerenciada pelo Spring Boot), compilando para a JVM 25. Build com Maven.
- Spring Boot 4.1, `JdbcClient` (sem JPA), Flyway, PostgreSQL (banco próprio, `scheduler`).
- SQS (LocalStack localmente) para os resultados dos Pix enviados.
- Testes: JUnit 5, AssertJ, fakes em memória (sem mocks), Testcontainers e ArchUnit (que lê o
  bytecode, então vale para Kotlin).

## Como funciona

Três conceitos, cada um com a sua tabela:

| Conceito | Tabela | O que é | Estados |
|---|---|---|---|
| Agendamento | `schedule` | A intenção: pagar X da conta A para B no dia D | `ACTIVE`, `CANCELLED`, `COMPLETED` |
| Execução | `schedule_execution` | O dia do pagamento, movido pelo job | `PENDING`, `PROCESSING`, `EXECUTED`, `FAILED`, `CANCELLED` |
| Tentativa | `schedule_attempt` | Cada chamada feita no dia, com a sua chave de idempotência | `EXECUTED`, `REFUSED` (vazio enquanto não se sabe) |

- **Na criação** valida o que dá para validar hoje: credenciais do tenant, conta pagadora ativa,
  conta destino (transferência) ou CPF/CNPJ do titular (Pix). Saldo só importa no dia. A data vai
  de amanhã até um ano.
- **No dia** o job tenta nas janelas **06:00, 12:00 e 18:00 de Brasília**. A primeira paga; as
  outras retentam o que pode mudar no dia (saldo insuficiente, conta bloqueada). Um motivo que não
  muda no dia (conta destino inexistente, AC03…) falha na hora.
- **Nunca paga duas vezes:** a chave da tentativa (`sched-{execução}-{número}`) é gravada antes da
  chamada. Timeout, erro 5xx ou queda do serviço repetem a chamada com a mesma chave, e o core ou
  o wallet-pix respondem com o pagamento original.
- **Várias instâncias** podem rodar: o job trava as execuções com `FOR UPDATE SKIP LOCKED`, e cada
  uma tem um lease de 2 minutos.
- **Pix:** o wallet-pix aceita o envio e o resultado chega depois, pelo tópico
  `pix-payment-events` (fila `wallet-scheduler-pix-events`). Se o evento não chegar em 10 minutos,
  o job pergunta de novo com a mesma chave.
- **Dia perdido:** se o serviço ficar fora o dia inteiro, a execução falha com `MISSED_DAY`. Nunca
  paga em outro dia.
- **Cancelar** só até a véspera.

## API

Aceita os JWTs do wallet-core (sem outro provedor de identidade). O tenant vem do token, nunca do
corpo.

| Método e caminho | Escopo | O que faz |
|---|---|---|
| `POST /v1/schedules` | `schedules:write` | Cria. Exige `Idempotency-Key`: a mesma chave com o mesmo pedido devolve o original (200); com outro pedido, 409 |
| `GET /v1/schedules?payerAccountId=` | `schedules:read` | Agendamentos de uma conta pagadora, data mais recente primeiro |
| `GET /v1/schedules/{id}` | `schedules:read` | Um agendamento, com a execução e as tentativas |
| `POST /v1/schedules/{id}/cancel` | `schedules:write` | Cancela (até a véspera) |

Transferência (`type` padrão):

```json
{
  "payerAccountId": "…",
  "executeOn": "2026-10-20",
  "amount": 150.00,
  "description": "Aluguel",
  "destination": { "branch": "0001", "number": "00100261", "checkDigit": "7" }
}
```

Pix (`type: "PIX"`, com `pix` no lugar de `destination`):

```json
{
  "type": "PIX",
  "payerAccountId": "…",
  "executeOn": "2026-10-20",
  "amount": 80.00,
  "pix": {
    "payerTaxId": "17482175342",
    "payee": { "ispb": "87654321", "branch": "0001", "accountNumber": "123456", "taxId": "52998224725", "name": "Maria Silva" }
  }
}
```

A resposta traz `execution` (status, próxima tentativa, transação, `failure` com código, mensagem
ao cliente e se ainda retenta no dia) e `attempts`. Erros vêm com um `code` estável: 400
(validação), 404, 409 (chave reusada), 422 (regra, por exemplo `CANCELLATION_DEADLINE_PASSED`),
503 (o core não respondeu).

## Pacotes

```
br.com.walletscheduler
├── domain          Schedule, Execution, Attempt, janelas, motivos; só Kotlin/JDK
├── application     ScheduleService (API), ExecutionService (job e eventos) e portas; sem Spring
├── adapter.in      API REST, consumidor da fila de eventos do Pix, job de execução
├── adapter.out     clientes do wallet-core e do wallet-pix, persistência JDBC
└── config          ligação com o Spring
```

As dependências apontam sempre para dentro; o `ArchitectureTest` quebra o build se não apontarem.

## Rodar localmente

```bash
cd ../wallet-core && docker compose up -d        # Postgres, observabilidade e wallet-core
cd ../wallet-pix && docker compose up -d         # só para agendar Pix (pix-service e LocalStack)
cd ../wallet-scheduler && docker compose up -d --build
curl localhost:8082/actuator/health              # {"status":"UP"}
```

O compose entra na rede do wallet-core e cria o banco `scheduler` com dois papéis:
`scheduler_owner` (Flyway) e `scheduler_app` (aplicação, sem DDL). Localmente o job acorda a cada
10 segundos (30 por padrão).

**Na IDE:** suba só o banco (`docker compose up scheduler-db-init`, que cria o banco e sai) e rode
`WalletSchedulerApplication` com `SCHEDULER_BUS_ENABLED=false` se o wallet-pix não estiver no ar.
Os padrões do `application.yml` já apontam para `localhost`.

**Testar sem esperar o dia:** a data mínima é amanhã. Para o job pegar um agendamento agora,
antecipe a tentativa no banco (só em ambiente local):

```bash
docker exec wallet-core-postgres-1 psql -U wallet_owner -d scheduler \
  -c "UPDATE schedule_execution SET next_attempt_at = now() WHERE status = 'PENDING';"
```

## Configuração

| Variável | Padrão | Para quê |
|---|---|---|
| `WALLET_CORE_URL` | `http://localhost:8080` | API e JWKS do wallet-core |
| `PIX_SERVICE_URL` | `http://localhost:8081` | Envio de Pix |
| `SCHEDULER_TENANT_1_CLIENT_ID` / `_SECRET` (e `_2_`) | os dois tenants do perfil `dev` | Credenciais com que cada agendamento é pago no dia |
| `SCHEDULER_POLL_INTERVAL` | `30s` | Intervalo do job |
| `SCHEDULER_BUS_ENABLED` | `true` | Liga o consumidor dos eventos de Pix |
| `AWS_ENDPOINT_URL` | `http://localhost:4566` | LocalStack |

As janelas, o lease e as esperas ficam em `scheduler.windows` e `scheduler.job` no
`application.yml`.

## Build e testes

```bash
mvn verify      # compila Kotlin, roda todos os testes e o ArchUnit
```

Os testes de `application/` rodam sem Docker: fakes em memória e um relógio movido à mão
(`MutableClock`), que anda pelas janelas do dia. O `SchedulerIntegrationTest` e o
`ApplicationStartupTest` usam PostgreSQL via Testcontainers e são pulados sem Docker.
