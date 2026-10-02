# ADR-007 — Observabilidade: métricas de negócio, tracing OpenTelemetry e alertas de auditoria

**Status:** aceita

## Contexto
"Para que o crescimento do sistema tenha isso por padrão" — ou seja, observabilidade não pode ser algo que se adiciona depois, sob pressão de um incidente. Ela precisa nascer junto com cada caso de uso novo, sem que o time precise lembrar de instrumentar manualmente toda vez.

## Decisão

### 1. Métricas via Micrometer, traces e logs via agente OpenTelemetry
Cada sinal tem um dono só:
- **Métricas** — API do Micrometer (`Observation`, `Counter`, `Gauge`) exposta pelo `PrometheusMeterRegistry` em `/actuator/prometheus`. No ponto de fronteira mais importante (transação de banco em `JdbcTransactionRunner`), um `Observation.observe(...)` gera o timer `wallet.db.transaction`.
- **Traces e logs** — **agente Java do OpenTelemetry** (`-javaagent`, configurado só por variáveis `OTEL_*`), que instrumenta sem código o HTTP (Spring MVC), o JDBC, o Hikari e os `@Scheduled`, e exporta spans e logs por OTLP.

A aplicação não tem nenhuma dependência de tracing no classpath (nem `micrometer-tracing-bridge-otel` nem `opentelemetry-exporter-otlp`) — ver a revisão abaixo.

> **Revisão (2026-10-02):** a versão original desta decisão usava o `micrometer-tracing-bridge-otel` para que o mesmo `Observation` gerasse span **e** timer, com os spans exportados pelo SDK embutido do Spring Boot. Quando o agente Java do OpenTelemetry foi adicionado ao `docker-compose.yml`, passaram a existir duas instrumentações de tracing concorrentes no mesmo processo — e a do Spring Boot nem chegava ao Collector dentro do container (o endpoint padrão era `localhost:4318`, o próprio container). Ficou só o agente: ele cobre mais coisas sem código (JDBC, Hikari, HTTP) e é configurado do mesmo jeito em qualquer ambiente. O custo é que `wallet.db.transaction` agora é só métrica, não um span próprio; no trace, a transação aparece como os spans JDBC do agente sob o span da requisição. Se esse span agrupador fizer falta, o caminho é `@WithSpan` (`opentelemetry-instrumentation-annotations`), não voltar o bridge.

### 2. Métricas de negócio como uma porta de saída (`MetricsRecorder`)
Os fatos de negócio (transação postada, transação rejeitada e por quê, cliente onboardado, resultado de uma auditoria) são reportados pelos serviços de aplicação através da porta `MetricsRecorder` — os mesmos serviços que já lançam eventos de domínio para o outbox. O `wallet-application` continua sem nenhuma dependência de framework; quem decide que isso vira um `Counter` do Micrometer é o adapter `MicrometerMetricsRecorder`, em `wallet-bootstrap`. Consequência prática: **qualquer caso de uso novo que injete `MetricsRecorder` ganha métricas de negócio automaticamente**, sem precisar reinventar onde instrumentar.

### 3. Auditoria deixa de ser só sob demanda: `AuditSweepJob`
Antes desta mudança, a auditoria (replay do ledger) só rodava quando alguém chamava `GET /v1/accounts/{id}/audit`. Isso significa que uma inconsistência podia existir sem que ninguém soubesse por dias. O `AuditSweepJob` roda a cada 5 minutos (configurável), percorre os tenants ativos e reaudita uma amostra limitada das contas mais recentemente movimentadas por tenant — as que têm mais chance de expor um bug de concorrência recém-introduzido. Qualquer inconsistência loga em `ERROR` e incrementa `wallet.audit.inconsistencies.total`, que é exatamente o que o alerta `WalletAuditInconsistencyDetected` observa.

**Por que amostra, não varredura completa:** rodar auditoria em toda conta, a cada 5 minutos, é um custo que cresce sem limite conforme a base de clientes cresce. Uma amostra de tamanho fixo por tenant mantém o custo do job previsível independentemente do crescimento — o trade-off é que uma inconsistência numa conta pouco movimentada pode demorar mais para ser pega. Uma varredura completa noturna é o próximo passo natural quando a tabela de contas justificar um job em lote dedicado.

### 4. Correlação por `tenant_id` nos logs, sem tocar a cadeia de filtros do Spring Security
`TenantLoggingInterceptor` roda como `HandlerInterceptor` (não como `Filter` de servlet) justamente para não competir em ordem com a cadeia de filtros do Spring Security — ele roda depois que o JWT já foi validado e o tenant já está disponível no contexto de segurança. Ele grava `tenant_id` no MDC do SLF4J; `trace_id`/`span_id` são injetados automaticamente no MDC pelo agente OpenTelemetry. O padrão de log em `application.yml` imprime os três, então qualquer linha de log — mesmo uma escrita fundo na camada de persistência, sem nenhuma referência a HTTP — pode ser filtrada por tenant ou seguida até o trace correspondente.

### 5. OpenTelemetry via OTLP, sem acoplar a um backend específico
O agente exporta traces e logs por **OTLP** (`OTEL_EXPORTER_OTLP_ENDPOINT`), o protocolo padrão da indústria — não a um backend específico. Em desenvolvimento, o `docker-compose.yml` sobe um OpenTelemetry Collector (`docker/otel-collector/config.yml`) que encaminha traces para o **Jaeger** e logs para o **Loki**, e um **Grafana** com os três data sources já provisionados (`docker/grafana/provisioning/`), ligados entre si: de um span se chega aos logs do mesmo `trace_id`, e de uma linha de log se chega ao trace. Em produção, é só apontar a mesma variável de ambiente para o Collector real (ou para o endpoint OTLP do seu vendor de observabilidade) — nada no código muda.

> **Correção (pós-publicação, histórica):** enquanto o tracing era feito pelo Spring Boot, a propriedade do endpoint mudou de `management.otlp.tracing.endpoint` (3.x) para `management.opentelemetry.tracing.export.otlp.endpoint` (4.1), e o nome antigo era ignorado em silêncio — nenhum span chegava ao Collector, sem erro no log. Com o agente (seção 1), essa propriedade não é mais usada; a configuração passou para as variáveis `OTEL_*`, que seguem a especificação do OpenTelemetry e não dependem da versão do Spring Boot.

### 6. Alertas como código, ao lado da aplicação
As regras de alerta (`docker/prometheus/alerts.yml`) vivem no repositório, versionadas junto do código que as métricas descrevem — se um nome de métrica muda, a mesma revisão de código pode atualizar o alerta. Três grupos:
- **Integridade do ledger** (`WalletAuditInconsistencyDetected`, `WalletAuditSweepStale`) — o mais crítico dos três; qualquer achado do replay é tratado como incidente de integridade de dados, não como métrica de rotina.
- **Transações** (taxa de rejeição, taxa de saldo insuficiente) — sinais de comportamento anômalo de clientes ou de um bug.
- **Infraestrutura** (backlog do outbox, erros de transação de banco, taxa de erro HTTP) — saúde operacional.

## Alternativas consideradas
| Opção | Por que não (agora) |
|---|---|
| `micrometer-tracing-bridge-otel` + SDK OpenTelemetry do Spring Boot (a escolha original) | Conflita com o agente Java quando os dois estão ativos, e só gera span onde há `Observation` ou instrumentação do Spring; o agente cobre JDBC/Hikari/HTTP sem código. Ver revisão na seção 1 |
| Reportar métricas de negócio direto do controller REST | Perde os casos de uso acionados fora do HTTP (ex.: o próprio `AuditSweepJob`, que chama `AuditLedgerUseCase` diretamente) — instrumentar no serviço de aplicação cobre todos os chamadores de uma vez |
| Baggage do OpenTelemetry para propagar `tenant_id` entre serviços | Reservado para quando existir uma chamada de serviço para serviço; hoje o sistema é um monólito modular, então MDC (só dentro do processo) já resolve a necessidade atual sem a complexidade extra de configurar campos remotos de baggage |
| Alertmanager rodando localmente | Fora do escopo desta entrega — `alerts.yml` já é carregado pelo próprio Prometheus (aba *Alerts*), o que é suficiente para ver os alertas disparando localmente; o roteamento para Slack/PagerDuty é uma decisão de cada ambiente de produção |

## Consequências
- (+) Qualquer novo caso de uso que injete `MetricsRecorder` ganha métricas de negócio consistentes; qualquer chamada nova a `JdbcTransactionRunner` ganha o timer `wallet.db.transaction`, e qualquer endpoint ou consulta JDBC nova ganha trace pelo agente sem código.
- (+) Uma inconsistência de ledger vira um alerta crítico sem depender de alguém chamar a API de auditoria manualmente.
- (−) A tag `tenant` nas métricas de negócio tem cardinalidade proporcional ao número de tenants (documentado em `MicrometerMetricsRecorder`); se a plataforma crescer para milhares de tenants, essa tag deve sair das métricas de alta frequência e migrar para os logs estruturados (que já carregam `tenant_id`).
- (−) `AuditSweepJob` audita uma amostra, não 100% das contas a cada ciclo — ver seção 3.
- (−) Traces e logs dependem do agente estar presente (`JAVA_TOOL_OPTIONS=-javaagent:...`); rodando a aplicação sem ele (ex.: `mvn spring-boot:run`), só as métricas funcionam e `trace_id`/`span_id` saem vazios no log.
- (+) Dashboards Grafana versionados no repositório (`docker/grafana/dashboards/`), como os alertas: renomear uma métrica e atualizar o painel cabem na mesma revisão.
- Não implementado nesta entrega, sugerido como próximo passo: roteamento de alertas para um canal real (Alertmanager + Slack/PagerDuty).
