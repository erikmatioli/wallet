# Wallet Core — backend white label para carteira digital

Core bancário de uma **digital wallet** multi-tenant: onboarding de clientes, emissão de **conta de pagamento** (padrão BCB/PIX, tipo `TRAN`), saldo, depósitos, saques e transferências — com **ledger de partidas dobradas**, controle de concorrência por linha e **reconstrução do saldo a partir dos eventos**.

| | |
|---|---|
| Linguagem / runtime | Java 25 (LTS), virtual threads |
| Framework | Spring Boot 4.1.1 (Spring Framework 7, Spring Security 7) |
| Banco de dados | PostgreSQL 17 (Flyway, Row Level Security) |
| Arquitetura | Hexagonal (ports & adapters), módulos Maven, regras verificadas com ArchUnit |
| Autenticação | HTTP Basic (client credentials do tenant) → JWT RS256 de vida curta + JWKS |

**Novo no projeto?** Antes de mexer no código, leia o [Guia do Desenvolvedor](docs/guia-do-desenvolvedor.md) — explica cada classe, cada método e como uma requisição percorre o sistema, pensado para quem nunca viu este código.

---

## 1. Visão geral da arquitetura

```
                       ┌──────────────────────────── wallet-bootstrap ────────────────────────────┐
                       │  @SpringBootApplication · UseCaseConfig (liga serviços às portas) · yml   │
                       └───────────────────────────────────────────────────────────────────────────┘
                                   │                        │                          │
         ┌─────────────────────────▼───────┐   ┌────────────▼─────────────┐  ┌─────────▼────────────────┐
         │  wallet-adapter-in-rest          │   │  wallet-application      │  │ wallet-adapter-out-*      │
         │  Controllers · Security (JWT)    │──▶│  Casos de uso (port.in)  │◀─│ persistence: JDBC/Flyway  │
         │  ProblemDetail (RFC 9457)        │   │  Portas de saída         │  │ messaging: publisher      │
         └──────────────────────────────────┘   │  (port.out) · sem Spring │  └───────────────────────────┘
                                                └────────────┬─────────────┘
                                                             │
                                                ┌────────────▼─────────────┐
                                                │  wallet-domain           │
                                                │  Java puro: Money, Account│
                                                │  LedgerTransaction, Leg   │
                                                └──────────────────────────┘
```

As dependências apontam sempre para dentro. `wallet-domain` e `wallet-application` não têm nenhuma dependência de framework; o `ArchitectureTest` falha o build se alguém violar isso.

| Módulo | Responsabilidade |
|---|---|
| `wallet-domain` | Modelo puro: `Money` (centavos), `TaxId` (CPF/CNPJ, inclusive **CNPJ alfanumérico**), `PaymentAccountNumber`, `Account`, `LedgerTransaction` (invariante de partidas dobradas), eventos de domínio |
| `wallet-application` | Casos de uso (`OnboardCustomer`, `MoveMoney`, `QueryAccount`, `AuditLedger`, `ProvisionTenant`) e portas de saída |
| `wallet-adapter-out-persistence` | PostgreSQL: repositórios via `JdbcClient` (SQL explícito), idempotência, outbox + relay, migrations Flyway |
| `wallet-adapter-out-messaging` | Publicação dos eventos do outbox (por padrão só loga; ponto de encaixe para Kafka/SNS) |
| `wallet-adapter-in-rest` | API REST, segurança Basic → JWT, tratamento de erros |
| `wallet-bootstrap` | Composição, configuração, observabilidade (`MicrometerMetricsRecorder`, `AuditSweepJob`), testes de arquitetura e de concorrência |

## 2. Como rodar

Pré-requisitos: JDK 25, Maven 3.9+, Docker.

```bash
# tudo em containers (Postgres + aplicação, perfil dev com tenant de demonstração)
docker compose up --build

# ou: só o banco em container e a aplicação local
docker compose up -d postgres
mvn -pl wallet-bootstrap -am spring-boot:run -Dspring-boot.run.profiles=dev
```

O perfil `dev` cria o tenant `demo-tenant` / segredo `demo-secret-change-me-please` (**somente desenvolvimento**).

```bash
mvn test        # unitários + ArchUnit + testes de concorrência (estes usam Testcontainers e exigem Docker)
```

### Roles do banco (importante)

A aplicação conecta como **`wallet_app`** (sem superuser, sem `BYPASSRLS`, sem `UPDATE/DELETE` nas tabelas do ledger). As migrations rodam como **`wallet_owner`**. Se você conectar a aplicação como superuser, o Row Level Security é ignorado. O script `docker/postgres/init.sql` cria a role.

## 3. Fluxo rápido (curl)

```bash
BASE=http://localhost:8080

# 1) Basic -> JWT
TOKEN=$(curl -s -u demo-tenant:demo-secret-change-me-please -X POST $BASE/v1/auth/token | jq -r .access_token)

# 2) Onboarding: cria o cliente e devolve a conta de pagamento
curl -s -X POST $BASE/v1/customers \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"name":"Maria Silva","taxId":"529.982.247-25","externalRef":"cli-001"}' | tee /tmp/onboard.json
ACC=$(jq -r .account.id /tmp/onboard.json)

# 3) Depósito (Idempotency-Key obrigatório)
curl -s -X POST $BASE/v1/accounts/$ACC/deposits \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -H "Idempotency-Key: $(uuidgen)" \
  -d '{"amount": 250.00, "description": "PIX recebido"}'

# 4) Transferência para outra conta pelo número (agência/conta/dígito) ou por id
#    (exemplo: use o número real da conta destino devolvido no onboarding dela)
curl -s -X POST $BASE/v1/transfers \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -H "Idempotency-Key: $(uuidgen)" \
  -d "{\"sourceAccountId\":\"$ACC\",\"destination\":{\"branch\":\"0001\",\"number\":\"00100002\",\"checkDigit\":\"9\"},\"amount\":40.00}"

# 5) Saldo, extrato e auditoria (replay do ledger)
curl -s -H "Authorization: Bearer $TOKEN" $BASE/v1/accounts/$ACC/balance
curl -s -H "Authorization: Bearer $TOKEN" "$BASE/v1/accounts/$ACC/statement?limit=20"
curl -s -H "Authorization: Bearer $TOKEN" $BASE/v1/accounts/$ACC/audit
```

## 4. API

| Método e rota | Escopo | Descrição |
|---|---|---|
| `POST /v1/auth/token` | Basic | Troca client id/secret por JWT (10 min) |
| `GET /.well-known/jwks.json` | público | Chaves públicas para validar o JWT |
| `POST /v1/customers` | `customers:write` | Onboarding + abertura de conta de pagamento (201) |
| `GET /v1/accounts` | `accounts:read` | Lista as contas do tenant, mais nova primeiro (paginação por `cursor`) |
| `GET /v1/accounts/lookup?branch=&number=&checkDigit=` | `accounts:read` | Busca uma conta pelo número bancário em vez do id |
| `GET /v1/accounts/{id}` | `accounts:read` | Dados da conta (ISPB, agência, número, dígito, tipo `TRAN`, saldo) |
| `GET /v1/accounts/{id}/balance` | `accounts:read` | Saldo |
| `GET /v1/accounts/{id}/statement?before=&limit=` | `accounts:read` | Extrato paginado por sequência (mais novo primeiro) |
| `POST /v1/accounts/{id}/deposits` | `ledger:write` | Entrada de dinheiro |
| `POST /v1/accounts/{id}/withdrawals` | `ledger:write` | Saída de dinheiro (nunca deixa o saldo negativo) |
| `POST /v1/transfers` | `ledger:write` | Transferência entre contas do tenant |
| `GET /v1/accounts/{id}/audit` | `ledger:audit` | Reconstrói o saldo pelos eventos e compara com o saldo armazenado |

Valores monetários são decimais em BRL com no máximo 2 casas (`"amount": 250.00`); internamente tudo é `long` em centavos.

Erros seguem `application/problem+json` com um campo estável `code`:

| HTTP | Exemplos de `code` |
|---|---|
| 400 | `VALIDATION_FAILED`, `INVALID_TAX_ID`, `INVALID_AMOUNT`, `IDEMPOTENCY_KEY_REQUIRED`, `INVALID_DESTINATION` |
| 404 | `ACCOUNT_NOT_FOUND`, `DESTINATION_ACCOUNT_NOT_FOUND` |
| 409 | `CUSTOMER_ALREADY_EXISTS`, `IDEMPOTENCY_KEY_REUSED` |
| 422 | `INSUFFICIENT_FUNDS`, `ACCOUNT_NOT_ACTIVE`, `SAME_ACCOUNT` |
| 503 | `TEMPORARILY_UNAVAILABLE` (contenção/timeout; repetir com a mesma `Idempotency-Key`) |

Repetir uma requisição com a mesma `Idempotency-Key` e o mesmo corpo devolve a resposta original (`200` + header `Idempotent-Replayed: true`) sem mover dinheiro de novo. A mesma chave com corpo diferente retorna `409`.

## 5. Decisões de projeto (resumo — detalhes em `docs/adr`)

1. **PostgreSQL** (ADR-001): ACID, lock por linha, constraints únicas, RLS, particionamento e operação madura em RDS/Aurora.
2. **Ledger de partidas dobradas, append-only** (ADR-002): toda transação gera lançamentos de débito e crédito que fecham em zero. Entradas e saídas de dinheiro têm como contrapartida contas internas de *settlement* (várias shards por tenant para não criar uma linha quente). Cada lançamento carrega `sequence_no` (sem buracos por conta) e `balance_after`, então o saldo é **totalmente reconstruível por replay**.
3. **Concorrência** (ADR-003): o saldo muda com **um único `UPDATE ... WHERE saldo + delta >= 0 RETURNING`**. O PostgreSQL trava a linha e reavalia a condição sobre a versão mais nova commitada, sem lost update e sem saldo negativo. As contas são travadas sempre em **ordem determinística de id** (A→B e B→A não geram deadlock), `lock_timeout` de 3 s e retry automático de falhas transitórias.
4. **Idempotência**: `INSERT ... ON CONFLICT DO NOTHING` em `(tenant, idempotency_key)` como primeiro passo da transação; requisições concorrentes com a mesma chave esperam o commit da primeira.
5. **Defesa em profundidade no banco**: `CHECK (balance >= 0)`, triggers que proíbem `UPDATE/DELETE/TRUNCATE` no ledger, trigger *deferred* que confere `Σ créditos = Σ débitos` no `COMMIT`, e a role da aplicação nem sequer tem privilégio de `UPDATE/DELETE` no ledger.
6. **Multi-tenancy por Row Level Security** (ADR-004): `tenant_id` em toda tabela de negócio, policy baseada em `set_config('app.tenant_id', …, true)` aplicado no início de cada transação. O tenant vem sempre do JWT, nunca do corpo da requisição.
7. **Basic → JWT** (ADR-005): segredo do tenant guardado só como hash (bcrypt), JWT RS256 com `tenant_id` e `scope`, JWKS público, rotas desconhecidas negadas por padrão.
8. **Outbox transacional**: eventos (`wallet.customer.onboarded.v1`, `wallet.transaction.posted.v1`) gravados na mesma transação do fato e entregues *at-least-once* por um relay (`FOR UPDATE SKIP LOCKED`). Os payloads não carregam CPF/CNPJ nem nome.
9. **Observabilidade por padrão** (ADR-007): métricas de negócio pelo Micrometer, traces e logs pelo agente Java do OpenTelemetry sem código de instrumentação, a auditoria do ledger roda sozinha em segundo plano, e uma inconsistência vira alerta crítico sem que ninguém precise chamar a API manualmente. Detalhes na seção 6.

## 6. Modelo da conta de pagamento

`ISPB (8) · agência (4) · número (8, sequência do banco) · dígito · tipo TRAN`. O dígito verificador usa módulo 11 sobre agência + número. **O BCB não impõe um algoritmo único de dígito**: cada instituição define o seu — troque `PaymentAccountNumber.computeCheckDigit` pela regra da sua instituição. Os campos exatos devem ser validados com o time regulatório antes de produção.

## 7. Observabilidade

A ideia central (ADR-007): instrumentação nasce junto do caso de uso, não é adicionada depois de um incidente.

- **Métricas de negócio** — os serviços de aplicação (`MoveMoneyService`, `OnboardCustomerService`, `AuditLedgerService`) reportam fatos de negócio pela porta `MetricsRecorder`, sem depender de Micrometer diretamente. O adapter `MicrometerMetricsRecorder` (em `wallet-bootstrap`) vira isso em contadores Prometheus:

  | Métrica | O que mede |
  |---|---|
  | `wallet_transactions_total{tenant,type,replayed}` | Depósitos/saques/transferências postados, e quantos foram replay de idempotência |
  | `wallet_transactions_rejected_total{tenant,type,reason}` | Rejeições antes de postar (`INSUFFICIENT_FUNDS`, `ACCOUNT_NOT_ACTIVE`, …) |
  | `wallet_customers_onboarded_total{tenant}` | Onboardings concluídos |
  | `wallet_audit_runs_total{tenant,result}` | Auditorias executadas, por resultado |
  | `wallet_audit_inconsistencies_total{tenant}` | Achados individuais de inconsistência — é isso que o alerta crítico observa |
  | `wallet_outbox_pending` | Eventos do outbox ainda não publicados |
  | `wallet_db_transaction_seconds_*{kind,tenant,error}` | Duração das transações de banco (`kind=read\|write`), com contagem de erro |

  Tudo isso fica exposto em `GET /actuator/prometheus`.

- **Tracing e logs (agente OpenTelemetry)** — a imagem Docker inclui o agente Java do OpenTelemetry (ativado por `JAVA_TOOL_OPTIONS=-javaagent:/app/opentelemetry-javaagent.jar`). Ele gera spans automaticamente para requisições HTTP, consultas JDBC, pool Hikari e jobs `@Scheduled`, e envia traces e logs por OTLP (`OTEL_EXPORTER_OTLP_ENDPOINT`) — tudo configurado pelas variáveis `OTEL_*` em `docker-compose.yml`, nada em `application.yml`. Localmente, o OpenTelemetry Collector encaminha traces para o **Jaeger** e logs para o **Loki**. A aplicação não tem dependência de tracing no `pom.xml`; o `Observation` de `JdbcTransactionRunner` gera só o timer `wallet_db_transaction_seconds_*` (a transação aparece no trace como os spans JDBC do agente).

- **Correlação nos logs** — `TenantLoggingInterceptor` grava `tenant_id` no MDC a cada requisição (depois que o JWT já foi validado); `trace_id`/`span_id` são injetados pelo agente. Toda linha de log, inclusive as escritas fundo na camada de persistência, sai como `[tenant_id=…,trace_id=…,span_id=…]` — dá para filtrar por tenant ou pular direto para o trace correspondente. Sem o agente (ex.: `mvn spring-boot:run`), `trace_id`/`span_id` saem vazios e não há traces; as métricas continuam funcionando.

- **Auditoria contínua** — `AuditSweepJob` roda a cada 5 minutos (`wallet.observability.audit-sweep.*`), reaudita uma amostra das contas mais recentemente movimentadas por tenant, e qualquer achado vira `ERROR` no log + incremento em `wallet_audit_inconsistencies_total`. Um `Gauge` (`wallet_audit_sweep_last_success_epoch_seconds`) também permite alertar se o próprio job parar de rodar.

- **Alertas como código** — `docker/prometheus/alerts.yml` traz regras prontas: `WalletAuditInconsistencyDetected` (crítico — qualquer achado do replay), `WalletAuditSweepStale`, taxa de rejeição/saldo insuficiente, backlog do outbox, erros de transação de banco e taxa de erro 5xx da API.

### Rodando a stack de observabilidade local

`docker compose up --build` já sobe tudo (a aplicação expõe métricas e o agente exporta traces e logs por padrão). Depois:

| O quê | Onde |
|---|---|
| Métricas cruas | `curl http://localhost:8080/actuator/prometheus` |
| Prometheus (consultas + aba *Alerts*) | http://localhost:9090 |
| Traces | http://localhost:16686 (Jaeger) |
| Métricas, logs e traces juntos | http://localhost:3000 (Grafana, `admin`/`admin`) |

O Grafana já sobe com os data sources Prometheus, Loki e Jaeger provisionados (`docker/grafana/provisioning/datasources/datasources.yml`), ligados entre si: num trace do Jaeger, o botão *Logs for this span* abre as linhas do Loki com o mesmo `trace_id`; numa linha de log do Loki, o link *View trace* abre o trace. Use *Explore* para navegar.

Na pasta **Wallet Core** há três dashboards provisionados (`docker/grafana/dashboards/*.json`, ligados entre si pelo menu do topo):

| Dashboard | O que mostra |
|---|---|
| **Wallet Core — Negócio** | Transações postadas, replays de idempotência, rejeições por motivo, onboardings, auditoria do ledger e varredura, com filtro por tenant. Os painéis que espelham um alerta usam a mesma fórmula e mostram o limiar |
| **Wallet Core — Serviço** | HTTP (vazão, status, latência p50/p95/p99 por endpoint, taxa de 5xx), transações de banco, pool Hikari, outbox e JVM (heap, GC, CPU, threads) |
| **Wallet Core — Logs** | Logs do Loki filtráveis por nível, tenant e texto; volume por nível, loggers com mais ERROR/WARN e as mensagens citadas nos alertas. Cada linha tem link para o trace no Jaeger |

Os JSONs são a fonte da verdade: edições pela UI do Grafana valem até o próximo restart. Para mudar um painel de vez, exporte o JSON (*Share → Export*) e substitua o arquivo.

Nenhum desses serviços é necessário para a aplicação funcionar — métricas, traces e logs só não têm para onde ir sem eles. Em produção, aponte `OTEL_EXPORTER_OTLP_ENDPOINT` para o seu próprio coletor/vendor e configure seu Prometheus (ou equivalente) para fazer scrape de `/actuator/prometheus`; os containers locais não fazem parte do deploy de produção.

## 8. Estado da verificação (leia antes de usar)

| Item | Situação |
|---|---|
| `wallet-domain` e `wallet-application` | **Compilados e testados**: 31 testes unitários passando (CPF/CNPJ, dígito, partidas dobradas, idempotência, transferências, saldo insuficiente, auditoria detectando adulteração, paginação). Compilados com JDK 21 (`--release 21`), pois o ambiente onde o projeto foi gerado não tinha JDK 25 |
| Adapters, bootstrap, migrations SQL, testes de concorrência | **Escritos e revisados, mas ainda não compilados nem executados**: o ambiente de geração não alcançava o Maven Central nem tinha Docker. Só houve checagem de sintaxe. Rode `mvn verify` com Docker; se algo divergir nas APIs do Spring Boot 4 / Jackson 3 / Testcontainers 2 (nomes de starters e pacotes mudaram na versão 4), o ajuste deve ser pontual |
| Testes de concorrência (`WalletCoreConcurrencyTest`) | Cobrem 200 movimentos simultâneos na mesma carteira, saques que não podem estourar o saldo, transferências opostas sem deadlock, mesma `Idempotency-Key` em paralelo, isolamento entre tenants (RLS) e imutabilidade do ledger. São ignorados automaticamente sem Docker |
| Observabilidade (métricas, tracing, `AuditSweepJob`, `docker-compose.yml`/Prometheus/Jaeger/Loki/Grafana) | **Escrita e revisada, ainda não executada de ponta a ponta** — mesma limitação de ambiente acima. Os nomes de métrica usados nas regras de `docker/prometheus/alerts.yml` foram conferidos contra a convenção de nomenclatura do Micrometer para Prometheus, mas vale rodar `docker compose up` e abrir a aba *Alerts* do Prometheus para confirmar que cada regra carrega sem erro de sintaxe PromQL |

## 9. Versionamento e CI/CD

Fluxo de branches, convenção de commits e processo de release estão em [`CONTRIBUTING.md`](CONTRIBUTING.md) (decisões e alternativas descartadas em `docs/adr/008-versionamento-e-cicd.md`). Resumo:

- **Trunk-based**: `main` sempre testável/publicável; branches curtas `feature/*`, `fix/*`, `chore/*`.
- **Conventional Commits** nos títulos de PR (`feat:`, `fix:`, `docs:`, …) — vira a mensagem de commit em `main` via squash merge.
- **Release por tag**: `git tag wallet-core-vX.Y.Z && git push origin wallet-core-vX.Y.Z` dispara `.github/workflows/release.yml`, que builda, testa de novo, publica a imagem no GHCR (`ghcr.io/erikmatioli/wallet/wallet-core:vX.Y.Z`) e cria a GitHub Release com notas automáticas. O prefixo `wallet-core-` existe porque o repositório é um monorepo (`wallet/`) — ver ADR-009.
- **CI em todo PR/push** (`.github/workflows/ci.yml`): `mvn verify` completo, build + scan de vulnerabilidades (Trivy) da imagem Docker, validação do título do PR.

Pipeline (`ci.yml` + `release.yml` + `build-test.yml` reutilizável) assume **GitHub Actions** e **GHCR** — não foi executado neste ambiente (sem acesso à API do GitHub); a sintaxe YAML foi validada, mas vale revisar o primeiro run real, sobretudo os passos de permissão do `GITHUB_TOKEN` (documentados no CONTRIBUTING).

## 10. Fora do escopo desta entrega (próximos passos sugeridos)

- API administrativa para criar/rotacionar credenciais de tenants (hoje: caso de uso `ProvisionTenant` + seeder de dev).
- Chaves JWT em KMS/Secrets Manager com rotação (`kid`), rate limiting por tenant e mTLS opcional.
- Criptografia/tokenização de CPF/CNPJ em repouso (LGPD) e política de retenção.
- Particionamento do `ledger_entry` por tempo (exige incluir a chave de partição nas constraints únicas), arquivamento e réplicas de leitura.
- Publisher real (Kafka/SNS/SQS), DLQ e limpeza do outbox publicado.
- Trilha de *hash chain* por conta para evidência criptográfica de adulteração (a auditoria por replay já existe; isso adicionaria uma segunda camada, independente do banco).
- Bloqueio/reserva de saldo (holds), limites, tarifas, estorno e integração com SPI/PIX (DICT, QR Code).
- Roteamento de alertas para um canal real (Alertmanager + Slack/PagerDuty) — hoje os alertas só aparecem na aba *Alerts* do Prometheus.
- Deploy contínuo de fato (o pipeline publica a imagem no GHCR, mas não a implanta em nenhum ambiente — depende de uma decisão de infraestrutura ainda não tomada) e cobertura de testes agregada (JaCoCo) no CI.

