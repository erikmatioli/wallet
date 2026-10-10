# Guia do Desenvolvedor — Plataforma Wallet

Este guia é para quem **nunca viu este repositório**. Ele explica o que cada produto faz, como
os produtos conversam, por que as coisas foram feitas assim e onde mexer para mudar algo. Não
substitui os READMEs (referência de API e comandos) nem as ADRs (o registro formal de cada
decisão). Funciona como o colega sênior que senta ao seu lado no primeiro dia.

Leia de cima para baixo na primeira vez. Depois, use o índice para voltar a uma parte.

## Índice

1. [A plataforma em uma página](#1-a-plataforma-em-uma-página)
2. [Vocabulário: o que você precisa saber antes](#2-vocabulário-o-que-você-precisa-saber-antes)
3. [Linguagens e tecnologias, camada por camada](#3-linguagens-e-tecnologias-camada-por-camada)
4. [A arquitetura que se repete em todo lugar: hexagonal](#4-a-arquitetura-que-se-repete-em-todo-lugar-hexagonal)
5. [Subindo tudo na sua máquina](#5-subindo-tudo-na-sua-máquina)
6. [Quem fala com quem: HTTP, barramento e bancos](#6-quem-fala-com-quem-http-barramento-e-bancos)
7. [wallet-core — o dono do dinheiro](#7-wallet-core--o-dono-do-dinheiro)
8. [wallet-pix — Pix com o Banco Central](#8-wallet-pix--pix-com-o-banco-central)
9. [wallet-scheduler — agendamentos](#9-wallet-scheduler--agendamentos)
10. [wallet-console — o console do operador](#10-wallet-console--o-console-do-operador)
11. [wallet-app — o app do cliente final](#11-wallet-app--o-app-do-cliente-final)
12. [Fluxos de ponta a ponta](#12-fluxos-de-ponta-a-ponta)
13. [As decisões que valem para todos os projetos](#13-as-decisões-que-valem-para-todos-os-projetos)
14. [Observabilidade: achando o que aconteceu](#14-observabilidade-achando-o-que-aconteceu)
15. [Onde alterar: receitas de manutenção](#15-onde-alterar-receitas-de-manutenção)
16. [Testes, CI e release](#16-testes-ci-e-release)
17. [Problemas comuns e onde procurar](#17-problemas-comuns-e-onde-procurar)
18. [Roteiro de estudo](#18-roteiro-de-estudo)

---

## 1. A plataforma em uma página

É uma **carteira digital white label**: uma empresa (a "fintech", que aqui chamamos de
**tenant**) contrata a plataforma e oferece contas de pagamento aos seus clientes. Vários tenants
usam a mesma instalação, e um nunca enxerga os dados do outro.

O repositório é um **monorepo** com sete produtos. Cada um tem build, deploy, banco e release
próprios:

| Produto | O que faz | Linguagem | Porta local |
|---|---|---|---|
| `wallet-core` | Clientes, contas, saldo, extrato e **o ledger** (o livro-razão onde o dinheiro mora) | Java 25 + Spring Boot 4 (Maven) | 8080 |
| `wallet-pix` | Recebe e envia Pix conversando com o SPI (o sistema do Banco Central) por filas | Java 25 + Spring Boot 4 (Maven) | 8081 (+ simulador 8090) |
| `wallet-scheduler` | Agenda transferências e Pix para uma data e executa no dia | Kotlin + Spring Boot 4 (Maven) | 8082 |
| `wallet-console` | Tela web **do operador** da fintech | TypeScript + Angular 21 | 4200 |
| `wallet-app` | App desktop **do cliente final** e seu backend (`app-api`) | Kotlin: Compose Desktop + Spring Boot 4 (Gradle) | 8083 / 8084 |
| `wallet-mobile` | **M-Wall**: o app do cliente final para celular (PWA), sobre o mesmo `app-api` | TypeScript: Angular 21 + service worker | 8086 / 8087 |
| `wallet-otp` | Códigos de uso único por e-mail: cadastro e login do app, aprovações no futuro | Kotlin + Spring Boot 4 (Maven) | 8085 (+ Mailpit 8025) |

```
                 OPERADOR DA FINTECH                       CLIENTE FINAL
                        │                                        │
                ┌───────▼────────┐                     ┌─────────▼─────────┐
                │ wallet-console │  Angular            │ app-desktop       │  Kotlin/Compose
                │ (nginx :4200)  │                     │ (janela Windows)  │
                └───────┬────────┘                     └─────────┬─────────┘
                        │ HTTP + JWT do tenant                   │ HTTP + token do cliente
                        │                                ┌───────▼────────┐
                        │                                │ app-api (BFF)  │ :8083 demo / :8084 segundo
                        │                                └──┬─────┬────┬──┘
                        │     ┌─────────────────────────────┘     │    │
          ┌─────────────▼─────▼──┐   HTTP    ┌──────────────┐     │    │
          │     wallet-core      │◀──────────│  wallet-pix  │◀────┘    │
          │ ledger · contas · JWT│◀──┐       │ pix-service  │          │
          └──────────────────────┘   │       └──┬────────▲──┘          │
                        ▲            │          │ SNS/SQS│             │
                        │ HTTP       │   ┌──────▼────────┴──┐          │
          ┌─────────────┴────────┐   │   │  barramento      │          │
          │  wallet-scheduler    │───┘   │  (LocalStack)    │◀── spi-simulator (finge ser o BCB)
          │  agendamentos        │◀──────│ pix-payment-events│
          └──────────────────────┘ fila  └──────────────────┘
                        ▲                                                │
                        └────────────────────────────────────────────────┘
```

Fora do desenho: o `app-api` também chama o **`wallet-otp`** (:8085), que manda os códigos de cadastro
e login por e-mail. Localmente, os e-mails caem no Mailpit (http://localhost:8025).

A regra mais importante do sistema inteiro: **só o `wallet-core` mexe em saldo**. O Pix, o
agendador, o console e o app pedem ao core para mover dinheiro, sempre pela API dele. Ninguém mais
tem uma tabela de saldo. Assim, as garantias de dinheiro (não ficar negativo, não duplicar, poder
reconstruir o saldo) só precisam estar certas num lugar.

---

## 2. Vocabulário: o que você precisa saber antes

**Do negócio**

- **Tenant** — a fintech cliente da plataforma. Tem credenciais (`client_id` / `client_secret`),
  um **ISPB** (o número de 8 dígitos que identifica a instituição no Pix) e uma agência. O perfil
  `dev` do core cria dois: `demo-tenant` (ISPB `12345678`) e `segundo-tenant` (`87654321`).
- **Cliente** — a pessoa (CPF) ou empresa (CNPJ) que tem conta numa fintech.
- **Conta de pagamento (`TRAN`)** — o tipo de conta do padrão do Banco Central para carteiras
  digitais: ISPB + agência + número + dígito.
- **Conta de liquidação (settlement)** — conta **interna** do tenant, usada como contrapartida
  quando dinheiro entra ou sai da plataforma. O cliente nunca a vê.
- **Partida dobrada** — todo movimento gera pelo menos um débito e um crédito que somam zero.
  Dinheiro nunca é criado nem destruído, só muda de conta.
- **Ledger** — o livro-razão: a lista imutável de lançamentos. O saldo é consequência dele.
- **SPI** — o Sistema de Pagamentos Instantâneos do Banco Central, por onde todo Pix passa.
- **pacs.008 / pacs.002 / pacs.004** — as mensagens ISO 20022 do Pix: ordem de pagamento, status
  (`ACSP` aceito, `ACSC` liquidado, `RJCT` rejeitado) e devolução.
- **EndToEndId** — o identificador único de um Pix, do começo ao fim.
- **Estorno × devolução** — *estorno* (`REFUND`): o SPI rejeitou o envio, o Pix nunca existiu e o
  débito é desfeito. *Devolução* (`RETURN`): o Pix foi liquidado e depois o recebedor mandou o
  dinheiro de volta.

**Técnico**

- **Idempotência** — repetir a mesma requisição não repete o efeito. Aqui, todo pedido que mexe em
  dinheiro leva o header `Idempotency-Key`. A mesma chave com o mesmo corpo devolve a resposta
  original. A mesma chave com outro corpo dá `409 IDEMPOTENCY_KEY_REUSED`.
- **JWT** — um token assinado que diz quem você é (`tenant_id`) e o que pode fazer (`scope`).
  Quem recebe confere a assinatura com a chave pública publicada no **JWKS**
  (`/.well-known/jwks.json`).
- **Escopo (scope)** — uma permissão dentro do token: `ledger:write`, `pix:send`,
  `schedules:read`…
- **RLS (Row Level Security)** — recurso do PostgreSQL que esconde as linhas de outros tenants
  direto no banco, mesmo que o código esqueça um `WHERE tenant_id = ...`.
- **Outbox** — gravar a mensagem a publicar numa tabela, **na mesma transação** do fato. Um
  processo separado (o *relay*) publica depois. Assim nunca existe "gravei, mas não publiquei" nem
  "publiquei, mas não gravei".
- **SNS / SQS** — serviços de mensageria da AWS. **SNS** é um *tópico*: quem publica não sabe quem
  lê. **SQS** é uma *fila*: guarda as mensagens até alguém consumi-las. Um tópico entrega uma cópia
  para cada fila assinante (*fan-out*). Localmente, quem faz o papel da AWS é o **LocalStack**.
- **DLQ (dead-letter queue)** — fila para onde vai uma mensagem que falhou várias vezes (aqui, 5),
  para alguém analisar em vez de ela ficar em loop para sempre.
- **Visibility timeout** — quando um consumidor lê uma mensagem do SQS, ela fica invisível por um
  tempo. Se ele não apagar a mensagem (porque falhou), ela reaparece e é entregue de novo.
- **At-least-once** — "pelo menos uma vez": mensagens podem chegar repetidas. Por isso todo
  consumidor **deduplica**, e todo efeito é idempotente.
- **BFF (Backend for Frontend)** — um backend feito para servir um frontend específico. O
  `app-api` é o BFF do app desktop.
- **Arquitetura hexagonal** — ver a [seção 4](#4-a-arquitetura-que-se-repete-em-todo-lugar-hexagonal).

---

## 3. Linguagens e tecnologias, camada por camada

| Camada | Tecnologia | Onde aparece | O que estudar primeiro |
|---|---|---|---|
| Banco | PostgreSQL 17, Flyway (migrations), RLS | todos os backends | `UPDATE ... RETURNING`, `ON CONFLICT`, `FOR UPDATE SKIP LOCKED`, índices únicos |
| Backend Java | Java 25, Spring Boot 4.1, `JdbcClient` (SQL explícito, **sem JPA**) | core, pix | `record`, `sealed interface`, `switch` com pattern matching, virtual threads |
| Backend Kotlin | Kotlin 2.3, Spring Boot 4.1 | scheduler, app-api | `data class`, `sealed interface`, `value class`, null safety, `when` |
| Mensageria | AWS SDK v2 (SNS/SQS) direto, sem Spring Cloud AWS | pix, scheduler | tópico × fila, raw delivery, visibility timeout, DLQ |
| Segurança | Spring Security: Basic → JWT RS256 (core); HS256 próprio (app-api) | todos | `SecurityFilterChain`, `JwtDecoder`, escopos |
| Frontend web | Angular 21: componentes standalone, **signals**, sem Zone.js; Vitest | console | `signal()`, `computed()`, `HttpClient`, interceptors, lazy routes |
| Desktop | Compose Multiplatform for Desktop, Ktor client, kotlinx.serialization, coroutines | app-desktop | `@Composable`, `StateFlow`, `LaunchedEffect`, `suspend` |
| Build | Maven (core, pix, scheduler), npm (console), **Gradle** (app — o plugin do Compose só existe para Gradle) | — | ciclo `verify`, version catalog |
| Testes | JUnit 5, AssertJ, **fakes em memória (sem mocks)**, Testcontainers, ArchUnit | todos os backends | por que fake e não mock (seção 16) |
| Observabilidade | OpenTelemetry (agente Java), Micrometer/Prometheus, Jaeger, Loki, Grafana | todos os backends | trace, span, métrica, MDC |
| Infra local | Docker Compose, LocalStack 4.12 | todos | redes externas do compose |

> **Por que duas linguagens no backend?** O core e o Pix nasceram em Java. O agendador e o app
> foram escritos em Kotlin **por escolha de aprendizado** (ADR-001 do scheduler), mantendo
> exatamente a mesma arquitetura, banco, testes e observabilidade. Compare um caso de uso Java
> (`wallet-pix/.../SendPixService.java`) com um Kotlin (`wallet-scheduler/.../ScheduleService.kt`)
> e você verá a mesma estrutura em sintaxes diferentes.

### Equivalências Java ↔ Kotlin que você vai ver o tempo todo

| Ideia | Java | Kotlin |
|---|---|---|
| Classe de dados imutável | `record Money(long cents) {}` | `data class Money(val cents: Long)` |
| Tipo que embrulha um valor, sem custo | `record AccountId(UUID value)` | `@JvmInline value class AccountId(val value: UUID)` |
| Conjunto fechado de casos | `sealed interface X permits A, B` | `sealed interface X` + `class A : X` |
| Tratar todos os casos | `switch (x) { case A a -> ...; }` | `when (x) { is A -> ... }` |
| Pode ser nulo | `Optional<T>` | `T?` |
| Injeção de dependência | construtor | construtor primário |

---

## 4. A arquitetura que se repete em todo lugar: hexagonal

Todos os backends (core, pix-service, scheduler, app-api) seguem **arquitetura hexagonal**, também
chamada *ports & adapters*. Entender isso uma vez vale para os quatro.

```
                    ┌────────────────────────────────────────┐
  HTTP, fila, job ─▶│ adapter.in   (controllers, listeners)  │
                    └───────────────┬────────────────────────┘
                                    ▼ chama
                    ┌────────────────────────────────────────┐
                    │ application  (casos de uso + PORTAS)   │  sem Spring
                    └───────┬───────────────────┬────────────┘
                            ▼ usa               │ define interfaces (portas)
                    ┌───────────────┐           ▼ implementadas por
                    │ domain        │   ┌──────────────────────────────────┐
                    │ regras puras  │   │ adapter.out (JDBC, HTTP, SNS)    │──▶ banco, outros serviços
                    └───────────────┘   └──────────────────────────────────┘
                    config: liga tudo (Spring @Bean)
```

- **`domain`** — as regras de negócio, só com a linguagem pura. Ex.: "uma transação precisa ter
  débitos = créditos", "só dá para cancelar o agendamento até a véspera".
- **`application`** — os **casos de uso** (`SendPixService`, `ScheduleService`, `AuthService`). Eles
  orquestram o domínio e falam com o mundo **só por interfaces**, as *portas* (`port.out`): "salve
  isso", "chame o core", "que horas são".
- **`adapter.in`** — o que **chama** a aplicação: controllers REST, consumidores de fila, jobs
  agendados.
- **`adapter.out`** — o que a aplicação **chama**, implementando as portas: repositórios JDBC,
  clientes HTTP, publicadores de mensagens.
- **`config`** / **`bootstrap`** — cria os objetos e liga as portas aos adapters.

**Por que isso importa no dia a dia:**

1. As regras rodam em teste sem banco e sem Spring, em milissegundos, usando **fakes** (uma
   implementação em memória da porta).
2. Trocar uma tecnologia (por exemplo, publicar no Kafka em vez de logar) muda **um adapter**, nada
   mais.
3. A direção das dependências é verificada pelo **ArchUnit**: se o domínio importar Spring, o
   build quebra. Não é convenção, é teste.

**Diferença de forma:** no `wallet-core`, cada camada é um **módulo Maven** separado
(`wallet-domain`, `wallet-application`, `wallet-adapter-*`, `wallet-bootstrap`). Nos outros
backends, é **um módulo só, com pacotes** (`domain`, `application`, `adapter.in`, `adapter.out`,
`config`), e o ArchUnit garante as mesmas fronteiras.

---

## 5. Subindo tudo na sua máquina

Pré-requisitos: Docker Desktop, JDK 25, Maven 3.9+, Node 22+ (console). O Gradle vem pelo wrapper
(`./gradlew`).

**A ordem importa**, porque todos os composes entram na rede do `wallet-core`
(`wallet-core_default`) e usam o Postgres dele:

```bash
cd wallet-core      && docker compose up -d           # Postgres, wallet-core, OTel, Jaeger, Prometheus, Loki, Grafana
cd ../wallet-pix    && docker compose up -d --build   # LocalStack (filas), pix-service, spi-simulator
cd ../wallet-scheduler && docker compose up -d --build
cd ../wallet-otp    && docker compose up -d --build   # códigos por e-mail (8085) e o Mailpit (8025)
cd ../wallet-app    && docker compose up -d --build   # app-api do demo-tenant (8083) e do segundo-tenant (8084)
cd ../wallet-mobile && docker compose up -d --build   # M-Wall (PWA) do demo-tenant (8086) e do segundo-tenant (8087)
cd ../wallet-console && npm ci && npm start           # console em http://localhost:4200
cd ../wallet-app    && ./gradlew :app-desktop:run     # app do cliente (demo-tenant)
```

| O quê | Endereço | Credencial de dev |
|---|---|---|
| wallet-core | http://localhost:8080 | `demo-tenant` / `demo-secret-change-me-please`; `segundo-tenant` / `segundo-tenant-secret-please` |
| pix-service | http://localhost:8081 | token do core com `pix:send` |
| spi-simulator | http://localhost:8090 | — |
| wallet-scheduler | http://localhost:8082 | token do core com `schedules:*` |
| app-api | http://localhost:8083 (demo) / 8084 (segundo) | CPF do cliente + código que chega por e-mail |
| wallet-otp | http://localhost:8085 | token do core com `otp:use` |
| Mailpit | http://localhost:8025 | — (os e-mails com os códigos) |
| console | http://localhost:4200 | as credenciais do tenant |
| M-Wall (PWA) | http://localhost:8086 (demo) / 8087 (segundo) | CPF do cliente + código que chega por e-mail |
| Grafana | http://localhost:3000 | pasta "Wallet Core" |
| Jaeger | http://localhost:16686 | — |
| Prometheus | http://localhost:9090 | — |
| LocalStack | http://localhost:4566 | `test` / `test` |

**Para parar sem perder dados:** `docker compose stop` ou `docker compose down`, **nunca com `-v`**
(o `-v` apaga o volume do Postgres, e com ele todos os bancos).

**Dinheiro para testar:** ninguém tem cash-in. Faça um depósito pelo console ou simule um Pix
chegando (`POST localhost:8090/simulate/incoming`, ou `python wallet-pix/scripts/pixdev.py incoming ...`).

---

## 6. Quem fala com quem: HTTP, barramento e bancos

### 6.1 Chamadas HTTP (síncronas)

| Quem chama | Quem recebe | Para quê | Token usado | Escopo exigido |
|---|---|---|---|---|
| console | core `/v1/**` | tudo do operador | JWT do tenant (login com client id/secret) | vários |
| console | scheduler `/v1/schedules` | agendamentos | o mesmo JWT do core | `schedules:read` / `schedules:write` |
| pix-service | core | holder-check, `pix-credits`, `pix-debits`, `reversals` | JWT do tenant dono do ISPB | `accounts:read`, `pix:receive`, `pix:send` |
| scheduler | core | buscar conta, transferir | JWT do tenant dono do agendamento | `accounts:read`, `ledger:write` |
| scheduler | pix-service | enviar Pix agendado | JWT do tenant | `pix:send` |
| app-api | core | abrir conta, saldo, extrato, transferência | JWT do **seu** tenant | vários |
| app-api | pix-service | enviar Pix, consultar status | JWT do seu tenant | `pix:send` |
| app-api | scheduler | agendamentos | JWT do seu tenant | `schedules:*` |
| app-desktop | app-api `/app/v1/**` | tudo do cliente | **token do cliente** (emitido pelo app-api) | — |

Repare num padrão: **quem emite o token de tenant é sempre o `wallet-core`**
(`POST /v1/auth/token`, com Basic). O pix-service, o scheduler e o console só **validam** esse
token pelo JWKS do core. O único token diferente é o do cliente final, que o `app-api` emite e só
ele aceita (seção 11).

O tenant **sempre vem do token, nunca do corpo da requisição**. É isso que impede um tenant de
mexer nos dados de outro trocando um id.

### 6.2 Barramento (assíncrono): tópicos e filas

Os tópicos e filas são criados por `wallet-pix/docker/localstack/init-bus.sh` quando o LocalStack
sobe.

| Tópico SNS | Quem publica | Fila SQS assinante | Quem lê a fila | Conteúdo |
|---|---|---|---|---|
| `spi-to-psp` | spi-simulator (o "Banco Central") | `wallet-pix-spi-inbound` (DLQ `wallet-pix-spi-inbound-dlq`) | pix-service (`SpiInboundHandler`) | pacs.008 chegando, pacs.002, pacs.004 |
| `psp-to-spi` | pix-service (via outbox) | `spi-simulator-inbound` | spi-simulator | pacs.008 saindo, pacs.002 ACSP/RJCT das nossas respostas |
| `pix-payment-events` | pix-service (via outbox) | `wallet-scheduler-pix-events` (DLQ `wallet-scheduler-pix-events-dlq`) | wallet-scheduler (`PixEventsListener`) | `PixEvent`: resultado final de cada Pix |
| `pix-payment-events` | — | `pix-events-dev` | ninguém (só para você inspecionar) | cópia dos mesmos eventos |

Configuração das filas: visibility timeout de 15 s; as DLQs recebem a mensagem depois de 5
tentativas.

**Os eventos do `pix-payment-events`** (`wallet-pix/pix-messages/.../PixEvents.java`):

| `type` | Quando |
|---|---|
| `PIX_RECEIVED` | Pix recebido, liquidado e creditado ao cliente |
| `PIX_RECEIVE_REJECTED` | Pix recebido que recusamos (com `reasonCode`) |
| `PIX_SENT_COMPLETED` | Pix enviado e liquidado pelo SPI |
| `PIX_SENT_REFUNDED` | Pix enviado rejeitado; débito estornado (com `reasonCode`) |
| `PIX_RETURN_RECEIVED` | Chegou devolução de um Pix que enviamos |

O evento leva `requestId` = a `Idempotency-Key` usada no envio. É assim que o agendador sabe de
qual agendamento é o resultado. **Nunca** leva CPF/CNPJ nem nome.

**E o `wallet-core`?** Ele grava eventos (`wallet.customer.onboarded.v1`,
`wallet.transaction.posted.v1`) no seu outbox, mas o publicador hoje é o `LoggingEventPublisher`:
**só loga**. Ninguém consome esses eventos ainda. Quando alguém precisar, troca-se esse adapter
por um que publique no SNS, e nada mais no core muda.

### 6.3 Bancos de dados

Há **um servidor Postgres** (o do compose do core) com **um banco por produto**. Nenhum serviço lê
o banco de outro: se precisa de um dado, chama a API.

| Banco | Dono | Tabelas principais | Roles |
|---|---|---|---|
| `wallet` | wallet-core | `tenant`, `customer`, `account`, `financial_transaction`, `ledger_entry`, `pix_transaction_detail`, `outbox_event` | `wallet_owner` (migrations), `wallet_app` (runtime, sem `UPDATE/DELETE` no ledger) |
| `pix` | pix-service | `pix_payment`, `inbound_message`, `outbox` | `pix_owner`, `pix_app` |
| `scheduler` | wallet-scheduler | `schedule`, `schedule_execution`, `schedule_attempt` | `scheduler_owner`, `scheduler_app` |
| `app` | app-api do demo-tenant | `customer_login`, `sent_pix` | `app_owner`, `app_app` |
| `app_segundo` | app-api do segundo-tenant | as mesmas | as mesmas |

**Duas roles por banco, sempre.** A role `*_owner` roda as migrations (cria tabelas). A role
`*_app` é a que a aplicação usa, com o mínimo de permissão. Se um bug ou um ataque tentar um
`DROP TABLE` pela aplicação, o banco recusa. Os bancos são criados por um container `*-db-init`
que roda um script e termina. Por isso você vê `Exited (0)` no `docker ps -a`: é o normal.

---

## 7. wallet-core — o dono do dinheiro

Guia detalhado, classe por classe: [`wallet-core/docs/guia-do-desenvolvedor.md`](wallet-core/docs/guia-do-desenvolvedor.md).
Modelo de dados: [`wallet-core/docs/data-model.md`](wallet-core/docs/data-model.md). ADRs 001 a
010 em `wallet-core/docs/adr/`.

### 7.1 Módulos

| Módulo Maven | Papel |
|---|---|
| `wallet-domain` | Java puro: `Money` (centavos em `long`), `TaxId`, `Account`, `LedgerTransaction`, `TransactionType`, eventos |
| `wallet-application` | Casos de uso (`MoveMoneyService`, `OnboardCustomerService`, `QueryAccountService`, `AuditLedgerService`…) e portas |
| `wallet-adapter-out-persistence` | JDBC, migrations Flyway, `OutboxRelay` |
| `wallet-adapter-out-messaging` | `LoggingEventPublisher` |
| `wallet-adapter-in-rest` | Controllers, segurança (Basic → JWT), `ApiExceptionHandler` |
| `wallet-bootstrap` | `@SpringBootApplication`, `UseCaseConfig` (liga casos de uso às portas), jobs, testes de arquitetura e concorrência |

### 7.2 API

| Rota | Escopo | Usado por |
|---|---|---|
| `POST /v1/auth/token` (Basic, `?scope=` opcional) | — | todos |
| `GET /.well-known/jwks.json` | público | pix, scheduler (validar tokens) |
| `POST /v1/customers` | `customers:write` | console, app-api |
| `GET /v1/accounts`, `/lookup`, `/findByTaxId` | `accounts:read` | console, scheduler, app-api |
| `POST /v1/accounts/holder-check` | `accounts:read` | pix, scheduler |
| `GET /v1/accounts/{id}`, `/balance`, `/statement` | `accounts:read` | console, app-api |
| `POST /v1/accounts/{id}/deposits`, `/withdrawals` | `ledger:write` | console |
| `POST /v1/transfers` | `ledger:write` | console, scheduler, app-api |
| `POST /v1/accounts/{id}/pix-credits` | `pix:receive` | pix |
| `POST /v1/accounts/{id}/pix-debits` | `pix:send` | pix |
| `POST /v1/transactions/{id}/reversals` | `pix:send` | pix (estorno) |
| `GET /v1/accounts/{id}/audit` | `ledger:audit` | console |

### 7.3 Tipos de transação

| Tipo | Movimento na conta do cliente | Contrapartida |
|---|---|---|
| `DEPOSIT` | crédito | conta de liquidação do tenant |
| `WITHDRAWAL` | débito | conta de liquidação |
| `TRANSFER` | débito na origem, crédito no destino | outra conta de cliente |
| `PIX_IN` / `PIX_RETURN_IN` / `PIX_REFUND` | crédito | conta de liquidação |
| `PIX_OUT` / `PIX_RETURN_OUT` | débito | conta de liquidação |

Todo `PIX_*` tem uma linha em `pix_transaction_detail` com a contraparte (nome, CPF/CNPJ
**mascarado**, ISPB, conta), o EndToEndId e o motivo. É o que o extrato mostra no bloco `pix` e o
que permite filtrar o extrato por `?product=PIX` (ADR-010).

### 7.4 As decisões que você precisa conhecer

1. **Saldo nunca é escrito direto.** Ele muda com **um único `UPDATE ... WHERE saldo + delta >= 0
   RETURNING`** (`JdbcAccountRepository.applyDelta`). O Postgres trava a linha e reavalia a
   condição. Resultado: sem *lost update* e sem saldo negativo, mesmo com 200 requisições
   simultâneas.
2. **Contas travadas em ordem de id.** A→B e B→A ao mesmo tempo não dão deadlock
   (`legsInLockOrder`).
3. **Ledger append-only.** Triggers proíbem `UPDATE`/`DELETE`, a role da aplicação nem tem esse
   privilégio, e um trigger no `COMMIT` confere que débitos = créditos.
4. **Saldo reconstruível.** Cada lançamento guarda `sequence_no` e `balance_after`. O
   `AuditLedgerService` refaz o saldo do zero e compara. O `AuditSweepJob` faz isso sozinho a cada
   5 minutos e dispara alerta se achar diferença.
5. **Idempotência no banco.** `INSERT ... ON CONFLICT (tenant_id, idempotency_key) DO NOTHING` é o
   primeiro passo da transação. Junto vai uma *impressão digital* (SHA-256 do pedido) para
   distinguir "repetiu" de "reusou a chave com outro pedido".
6. **Multi-tenant por RLS.** No começo de cada transação, `set_config('app.tenant_id', ...)`, e as
   policies do Postgres filtram o resto.
7. **Várias contas de liquidação por tenant** (*shards*), para os depósitos de um tenant não
   disputarem a mesma linha.

---

## 8. wallet-pix — Pix com o Banco Central

README: [`wallet-pix/README.md`](wallet-pix/README.md). Decisões: [ADR-001](wallet-pix/docs/adr/001-servico-pix-e-barramento.md).
Roteiro de testes manual: [`guia-testes-pix.md`](wallet-pix/docs/guia-testes-pix.md).

### 8.1 Módulos e pacotes

| Módulo | Papel |
|---|---|
| `pix-messages` | Contratos: `SpiMessages` (pacs.008/002/004 em JSON com as tags ISO 20022) e `PixEvents` |
| `pix-service` | O serviço (hexagonal por pacotes) |
| `spi-simulator` | Finge ser o SPI. **Nunca vai para produção** |

Dentro do `pix-service`:

| Pacote | Classes que importam |
|---|---|
| `domain` | `PixPayment` (máquina de estados), `RejectionReason` (AC03, AC06…), `SpiIds` (gera EndToEndId) |
| `application` | `ReceivePixService`, `SendPixService`, `StatusReportRouter` (decide se um pacs.002 é de entrada ou de saída), `MaxAmountPolicy`, `SpiMessageFactory`, `PixEventFactory` |
| `adapter.in.rest` | `PixPaymentController` (`POST/GET /v1/pix/payments`) |
| `adapter.in.sqs` | `SpiInboundHandler` (o que fazer com cada mensagem da fila) |
| `adapter.bus` | `SqsQueueConsumer` (o loop que lê o SQS), `OutboxRelay` (publica no SNS), `TraceContext` |
| `adapter.out.walletcore` | `WalletCoreClient` (token por tenant, chamadas ao core) |
| `adapter.out.persistence` | `JdbcPixPaymentRepository`, `JdbcInboundMessageLog`, `JdbcOutbox` |

### 8.2 Estados de um Pix (`PixPayment`)

```
RECEBIDO (INBOUND):  ACCEPTED ──ACSC──▶ CREDITED
                     ACCEPTED ──RJCT──▶ REJECTED        (ou REJECTED direto se o holder-check falhar)

ENVIADO (OUTBOUND):  SENT ──ACSC──▶ COMPLETED ──pacs.004──▶ RETURNED
                     SENT ──RJCT──▶ REFUNDED   (débito estornado)
```

### 8.3 Como o tenant vira participante do Pix

Cada tenant tem um ISPB. Em `application.yml`, `pix.participants` mapeia **ISPB → credenciais do
tenant**. Quando chega uma mensagem para o ISPB `87654321`, o serviço pega um token do core como
`segundo-tenant` e faz tudo como ele. A RLS do core continua valendo.

Um Pix entre dois tenants nossos passa pelo serviço **duas vezes**: uma como pagador (linha
OUTBOUND) e outra como recebedor (linha INBOUND), com o mesmo EndToEndId.

### 8.4 Garantias: entrega pelo menos uma vez, efeito exatamente uma vez

| Problema | Proteção |
|---|---|
| Mesma mensagem chega duas vezes | `inbound_message`: id da mensagem gravado com `ON CONFLICT DO NOTHING` na mesma transação |
| Serviço cai depois de mudar o estado e antes de publicar | Outbox: a mensagem só existe se o estado foi commitado |
| Serviço cai depois de chamar o core e antes de commitar | A mensagem é reprocessada; o core devolve a mesma transação pela `Idempotency-Key` (crédito: chave = EndToEndId; débito: `pix-debit-<chave>`) |
| Duas entregas simultâneas | Versão otimista em `pix_payment`: uma vence, a outra vira duplicata |

### 8.5 Regras do envio, em ordem

1. Políticas (`PaymentPolicy`; hoje só `MaxAmountPolicy`, 5.000,00) — **antes** de chamar o core.
2. O CPF/CNPJ do pagador tem de ser do titular (`holder-check`).
3. Débito atômico no core (`pix-debits`, tipo `PIX_OUT`). **Sem consulta prévia de saldo**: o
   saldo pode mudar entre a consulta e o débito, e o próprio débito já recusa.
4. `pix_payment` como `SENT` + pacs.008 no outbox, na mesma transação → `202`.

### 8.6 Simulador do SPI

- Roteia entre os dois tenants do seed.
- Faz o papel de um banco externo fictício, ISPB `99999999`: responde ACSC, ou RJCT AC03 quando o
  valor termina em `,99` (para testar o estorno).
- `POST /simulate/incoming` (Pix chegando), `POST /simulate/return` (devolução),
  `GET /simulate/messages` (o que passou).
- O estado fica em memória: reiniciar o simulador esquece os Pix em andamento.

---

## 9. wallet-scheduler — agendamentos

README: [`wallet-scheduler/README.md`](wallet-scheduler/README.md). Decisões:
[ADR-001](wallet-scheduler/docs/adr/001-servico-de-agendamento.md) (leia a seção "Ajustes feitos
na implementação").

### 9.1 O modelo: agendamento → execução → tentativa

| Tabela | O que é | Estados |
|---|---|---|
| `schedule` | A intenção do cliente: conta pagadora, tipo (transferência ou Pix), destino, valor, data | `ACTIVE`, `CANCELLED`, `COMPLETED` |
| `schedule_execution` | O agendamento rodando num dia | `PENDING`, `PROCESSING`, `EXECUTED`, `FAILED`, `CANCELLED` |
| `schedule_attempt` | Cada chamada feita no dia, com horário, código e motivo legível | `EXECUTED`, `REFUSED` |

Separar agendamento de execução já deixa espaço para a recorrência: hoje há uma execução por
agendamento; com recorrência, haverá uma por ocorrência, sem mudar as tabelas.

### 9.2 Pacotes

| Pacote | Classes |
|---|---|
| `domain` | `Schedule`, `Execution`, `ExecutionWindows` (06:00, 12:00, 18:00 em Brasília), `FailureReasons` (código → texto e "retenta no dia?") |
| `application` | `ScheduleService` (criar, listar, detalhar, cancelar), `ExecutionService` (rodar tentativa, fechar pelo evento do Pix) |
| `adapter.in.rest` | `ScheduleController` (`/v1/schedules`) |
| `adapter.in.job` | `ExecutionJob` (`@Scheduled` a cada 30 s) |
| `adapter.in.messaging` | `PixEventsListener` (fila `wallet-scheduler-pix-events`) |
| `adapter.out.*` | `WalletCoreClient`, `WalletCoreTokens` (cache de token por tenant), `PixServiceClient`, `JdbcScheduleRepository` |

### 9.3 Como uma execução acontece

1. O `ExecutionJob` acorda a cada 30 s e pega até 50 execuções vencidas com
   **`FOR UPDATE SKIP LOCKED`**: várias instâncias podem rodar sem pegar a mesma execução. Ele toma
   a execução por 2 minutos (*lease*).
2. Grava a tentativa com a chave **`sched-<execução>-<número da tentativa>`** **antes** de chamar o
   serviço.
3. Chama o core (transferência) ou o wallet-pix (Pix) e classifica a resposta numa
   `sealed interface`:
   - **executou** → grava o resultado;
   - **recusado** → se o motivo pode mudar no dia (saldo insuficiente, conta bloqueada), tenta na
     próxima janela; senão, `FAILED` na hora;
   - **resultado desconhecido** (timeout, 5xx) → repete **a mesma tentativa com a mesma chave**
     1 minuto depois. Se o dinheiro já tinha saído, o serviço devolve a resposta original e nada é
     pago duas vezes.
4. **Pix:** o envio aceito deixa a execução `PROCESSING`. O resultado chega pela fila
   (`PIX_SENT_COMPLETED` → `EXECUTED`; `PIX_SENT_REFUNDED` → `FAILED` com o motivo do SPI), casado
   pelo `requestId`. Se o evento não chegar em 10 minutos, o job pergunta de novo ao wallet-pix com
   a mesma chave.
5. Se o evento chegar **antes** de o agendador ter gravado o `202` (o simulador é muito rápido), o
   listener devolve a mensagem para a fila (`NotReadyYetException`), e ela volta depois do
   visibility timeout.
6. Se o serviço ficar fora do ar o dia inteiro: `FAILED` com `MISSED_DAY`. Nunca paga em outro dia.

### 9.4 Regras de negócio

- Data a partir de amanhã. Cancelamento até 23:59 da véspera (Brasília). Sem edição: cancela e cria
  outro.
- Dias não úteis não adiam a execução.
- O agendador **não decide saldo**: quem decide no dia é o core ou o wallet-pix.
- Isolamento: toda consulta filtra por `tenant_id`. Não há RLS neste banco porque o job precisa ver
  as execuções de todos os tenants.

---

## 10. wallet-console — o console do operador

README: [`wallet-console/README.md`](wallet-console/README.md). Decisões:
[ADR-001](wallet-console/docs/adr/001-arquitetura-do-console.md).

### 10.1 Para quem é

Para o **operador da fintech**, não para o cliente final. O login é com o client id/secret do
tenant, e o operador vê **todas** as contas daquele tenant.

### 10.2 Estrutura

```
src/app/
├── core/     AuthService, auth.interceptor (anexa o Bearer), auth.guard (protege rotas),
│             WalletApiService (cliente HTTP tipado), models.ts (espelho dos DTOs do backend),
│             transaction-labels.ts e schedule-labels.ts (código → texto em português)
├── pages/    login, home (lista e busca de contas), onboard (abre conta),
│             account (saldo, extrato, movimentos, auditoria)
│               ├── entry-detail/  popup com o detalhe do lançamento (dados já carregados, sem nova chamada)
│               └── schedules/     seção de agendamentos da conta
└── shared/   problem-banner (mostra erros no formato problem+json)
```

### 10.3 Decisões

- **Chamadas sempre relativas** (`/v1/...`). Em dev, o `proxy.conf.json` encaminha; no Docker, o
  `nginx.conf`. Regra de roteamento: `/v1/schedules` → scheduler (8082); o resto de `/v1/**` e
  `/.well-known/**` → core (8080). O prefixo mais longo vence. Resultado: **zero CORS** e nenhuma
  URL de backend dentro do bundle.
- **Sessão em `sessionStorage`**: some ao fechar a aba. O secret nunca é guardado.
- **Uma `Idempotency-Key` nova por clique** (`crypto.randomUUID()`).
- **Signals** em vez de RxJS para estado local; componentes standalone com lazy loading.
- **Sem biblioteca de UI**: SCSS com design tokens (variáveis CSS).
- **Microfrontend preparado, mas não federado**: não existe um shell para hospedá-lo ainda.

**Ao mudar um contrato do backend**, o primeiro arquivo a atualizar é `core/models.ts`.

---

## 11. wallet-app — o app do cliente final

README: [`wallet-app/README.md`](wallet-app/README.md). Decisões:
[ADR-001](wallet-app/docs/adr/001-app-do-cliente.md).

### 11.1 Três módulos Gradle

| Módulo | Papel |
|---|---|
| `app-contract` | DTOs `@Serializable` (kotlinx.serialization) usados **pelos dois lados**: mudou aqui, o compilador aponta onde ajustar no servidor e no desktop |
| `app-api` | O BFF: Spring Boot + Kotlin, hexagonal por pacotes, banco de logins |
| `app-desktop` | Compose for Desktop: telas, ViewModels com `StateFlow`, cliente HTTP Ktor |

### 11.2 Por que um BFF, e não o desktop chamando o core direto?

O core só conhece **tenants**, não clientes finais. Se o desktop tivesse o client secret do tenant,
qualquer cliente que abrisse o executável teria acesso a **todas** as contas da fintech. O
`app-api`:

- guarda o secret do tenant **só no servidor**;
- tem o próprio login, **sem senha**: o cliente informa o CPF e recebe um código no e-mail
  cadastrado, gerado pelo `wallet-otp` (ADR-002 do wallet-app). A resposta é a mesma para um CPF com
  ou sem conta;
- emite um **token do cliente** (JWT HS256, 30 minutos) com `account_id` e `tenant`;
- em toda chamada, usa o `account_id` **do token**. O desktop nunca diz de qual conta é o pedido.

### 11.3 Um `app-api` por tenant

Cada fintech tem **a sua instância** do `app-api`, com as suas credenciais, o seu banco de logins e
a sua chave de sessão:

| Tenant | Instância | Porta | Banco |
|---|---|---|---|
| `demo-tenant` | `app-api` | 8083 | `app` |
| `segundo-tenant` | `app-api-segundo` | 8084 | `app_segundo` |

As duas rodam **a mesma imagem**: só a configuração muda (`APP_TENANT_CLIENT_ID`,
`APP_TENANT_CLIENT_SECRET`, `APP_SESSION_SECRET`, `DB_URL`). O token leva o claim `tenant` e o
issuer `app-api:<tenant>`, e cada instância recusa tokens de outra, mesmo que alguém configure as
duas com a mesma chave por engano. `APP_SESSION_SECRET` **não tem valor padrão**: sem ele, a
instância nem sobe.

O desktop escolhe o backend por `-Dwallet.api.url=...` ou `WALLET_APP_API_URL` (padrão
`http://localhost:8083/`) e mostra o tenant no título da janela (`GET /app/v1/info`).

### 11.4 Isolamento entre clientes do mesmo tenant

O wallet-pix e o scheduler separam **tenants**, mas não sabem que existem clientes finais. O
`app-api` completa:

- **Pix:** a tabela `sent_pix` guarda quem enviou cada Pix; só esse cliente consulta o status.
- **Agendamentos:** antes de detalhar ou cancelar, confere se a conta pagadora é a do token. O
  "pode cancelar?" (`canCancel`) é calculado no servidor, em horário de Brasília, e não pelo relógio
  do computador do cliente.
- **Idempotência:** os outros serviços guardam chaves **por tenant**, e todos os clientes do app são
  o mesmo tenant. A chave enviada adiante é `app-` + 40 caracteres do SHA-256 de
  `login:chave do desktop` (`IdempotencyKeys`): dois clientes nunca colidem, a mesma tentativa
  repetida gera a mesma chave, e cabe no limite de 64 caracteres do wallet-pix.

### 11.5 Cadastro que se recupera sozinho

O login nasce `PENDING` **antes** de chamar o core e vira `ACTIVE` depois. Se o `app-api` cair no
meio, o próximo cadastro com o mesmo CPF acha o login pendente, recebe "cliente já existe" do core
e recupera a conta pelo CPF, sem criar outra. Um cliente que já existia no core **sem** login
pendente foi criado fora do app (pelo console): o cadastro é recusado, porque o e-mail digitado no
cadastro não prova quem é o dono da conta. Esse cliente usa **Entrar**: o código vai para o e-mail que o
operador cadastrou no console, e o primeiro código conferido cria o login ligado à conta que já existe
(ADR-003). Sem e-mail no cadastro do core, o atendimento precisa incluí-lo.

O cadastro só chega aqui depois de o cliente confirmar o código enviado ao e-mail que informou: é
isso que prova que o e-mail é dele.

### 11.6 O desktop por dentro

```
Main.kt              application { } → Window; cria SessionStore e AppApiClient
data/AppApiClient    todas as chamadas HTTP (Ktor); erro vira AppApiException;
                     SESSION_EXPIRED encerra a sessão
data/SessionStore    o token, só em memória (fechar o app = sair)
ui/Navigator         pilha de telas: sealed interface Screen
                     (Login, Signup, Home, Statement, Transfer, Pix, Schedules, NewSchedule)
ui/App.kt            escolhe a tela pelo estado; bloqueio por inatividade (5 min sem mouse/teclado)
ui/Format.kt         dinheiro, datas e textos (formato brasileiro)
ui/<área>/           uma tela = um @Composable + um ViewModel com StateFlow
```

**Padrão de cada tela:**

- O ViewModel expõe `StateFlow<UiState>` e funções de ação.
- A tela faz `collectAsState()` e desenha o estado.
- Telas de pagamento têm três passos, num `sealed interface Step`: `Form` → `Confirm` → `Done`.
- A `Idempotency-Key` nasce no passo `Confirm`. Se der erro, a tela continua na confirmação com a
  mesma chave: clicar "Confirmar" de novo é uma repetição, não um segundo pagamento. Voltar ao
  formulário para mudar algo gera uma chave nova.

**Empacotar:** `./gradlew :app-desktop:createDistributable` gera o `Wallet.exe` com a JVM embutida.
A JVM do pacote só leva os módulos listados em `build.gradle.kts`. Se faltar `java.net.http`, o app
não abre e não mostra erro; se faltar `jdk.localedata`, o dinheiro aparece em formato inglês. Esses
dois problemas **só aparecem no executável**, nunca no `gradlew run`.

### 11.7 O motor de códigos (wallet-otp)

Um serviço próprio, em Kotlin, com banco `otp`, chamado pelo `app-api` com o token do tenant
(escopo `otp:use`). Ele não conhece clientes: quem chama diz **quem** (CPF/CNPJ), **para quê**
(`SIGNUP`, `LOGIN`, `PAYMENT_APPROVAL`) e **para onde** mandar. Regras: 6 dígitos, 5 minutos, uso
único, 5 tentativas, um código novo invalida o anterior, 1 envio por minuto e 5 por hora por CPF, 10
por hora por e-mail. No banco fica só o HMAC do código e o e-mail mascarado. Um "contexto" opcional
prende o código ao que ele aprova (no cadastro, o e-mail; num pagamento, valor e recebedor).
Detalhes no [README](wallet-otp/README.md) e na ADR-001 do wallet-otp.

### 11.8 M-Wall: o mesmo app no celular (wallet-mobile)

README: [`wallet-mobile/README.md`](wallet-mobile/README.md). Decisões:
[ADR-001](wallet-mobile/docs/adr/001-pwa-do-cliente.md).

Um segundo cliente do **mesmo `app-api`**: nenhum endpoint novo. É um PWA em Angular 21 (a stack do
console), instalável pela tela inicial do celular, com nome e identidade próprios, M-Wall, em laranja.

- **Mesma origem:** um nginx serve o PWA e encaminha `/app/v1/**` para o `app-api` do tenant
  (`APP_API_UPSTREAM`). Sem CORS e sem URL de backend no bundle; um container por tenant (8086 e 8087).
- **O service worker só guarda o app** (HTML, JS, CSS, fonte, ícones), nunca uma resposta de
  `/app/v1`: saldo e extrato não ficam no aparelho.
- **Sessão só em memória**, como no desktop: recarregar a página ou fechar o app pede o código de novo.
  5 minutos sem toque também encerram, inclusive quando o app volta do segundo plano.
- **Os ViewModels viram "flows":** `src/app/flows/*.flow.ts` são classes com signals, sem DOM, testadas
  com Vitest e uma API falsa. As telas só desenham o estado. Os mesmos três passos nos pagamentos, com a
  `Idempotency-Key` nascendo na confirmação.
- **O contrato é repetido** em `src/app/core/contract.ts`. O kotlinx.serialization **omite campo com
  valor padrão** (`attempts = emptyList()`, `resendAfterSeconds = 60`): no TypeScript esses campos são
  opcionais e quem lê aplica o mesmo padrão.

---

## 12. Fluxos de ponta a ponta

### 12.1 Cliente abre conta pelo app

```
desktop ── POST /app/v1/signup/start {nome, cpf, e-mail} ──▶ app-api
app-api ── POST /v1/otp/challenges (SIGNUP, contexto = e-mail) ──▶ wallet-otp ── e-mail ──▶ cliente
desktop ── POST /app/v1/signup/confirm {challengeId, código, nome, cpf, e-mail} ──▶ app-api
app-api ── POST /v1/otp/challenges/{id}/verify ──▶ wallet-otp: confere
app-api:  grava customer_login PENDING com o e-mail
app-api ── POST /v1/customers ──▶ core: cria cliente + conta TRAN, evento no outbox
app-api:  customer_login → ACTIVE com account_id
desktop ◀── 201 com o token do cliente
```

### 12.1b Cliente entra no app

```
desktop ── POST /app/v1/login/start {cpf} ──▶ app-api: limite de pedidos igual para todo CPF
app-api:  CPF com login? pede o código (LOGIN) ao wallet-otp para o e-mail do login
          Sem login, mas cliente do console com e-mail? código para o e-mail do core (ADR-003);
            o primeiro código conferido cria o login ligado à conta existente
          Nada disso? inventa um challengeId que nunca confere. A resposta é a mesma.
desktop ── POST /app/v1/login/confirm {challengeId, cpf, código} ──▶ app-api ── verify ──▶ wallet-otp
desktop ◀── 200 com o token do cliente (30 minutos)
```

### 12.2 Transferência pelo app

```
desktop ── POST /app/v1/transfers (token do cliente, Idempotency-Key da confirmação) ──▶ app-api
app-api:  account_id vem do token; chave = app-<sha256(login:chave)>
app-api ── POST /v1/transfers (JWT do tenant, ledger:write) ──▶ core
core:     uma transação de banco: idempotência → UPDATE do saldo das duas contas (em ordem de id)
          → ledger_entry ×2 → outbox_event → COMMIT
desktop ◀── comprovante
```

### 12.3 Pix enviado do demo-tenant para o segundo-tenant

```
1. app-api(demo) ── POST /v1/pix/payments (pix:send) ──▶ pix-service
2. pix-service: política → holder-check do pagador → POST pix-debits (PIX_OUT) no core
                → pix_payment SENT + pacs.008 no outbox → 202
3. OutboxRelay ── pacs.008 ──▶ SNS psp-to-spi ──▶ fila spi-simulator-inbound ──▶ simulador
4. simulador ── pacs.008 ──▶ SNS spi-to-psp ──▶ fila wallet-pix-spi-inbound ──▶ pix-service
   (agora como RECEBEDOR, ISPB 87654321 → credenciais do segundo-tenant)
5. pix-service: holder-check do recebedor → pix_payment INBOUND ACCEPTED → pacs.002 ACSP ──▶ simulador
6. simulador ── pacs.002 ACSC (para os dois lados) ──▶ pix-service
7. pix-service: lado recebedor → POST pix-credits (PIX_IN) no segundo-tenant → CREDITED → evento PIX_RECEIVED
                lado pagador   → COMPLETED → evento PIX_SENT_COMPLETED
8. app-api(demo) consulta GET /v1/pix/payments/{e2e} quando o desktop pergunta o status
```

Tudo isso aparece como **um trace só** no Jaeger: o `traceparent` viaja como atributo das
mensagens SNS/SQS e atravessa o outbox.

### 12.4 Pix rejeitado → estorno

```
simulador ── pacs.002 RJCT AC03 ──▶ pix-service
pix-service ── POST /v1/transactions/{débito}/reversals ──▶ core (gera PIX_REFUND, uma única vez)
pix-service: SENT → REFUNDED → evento PIX_SENT_REFUNDED (reasonCode AC03)
```

Para provocar: um Pix para o ISPB `99999999` com valor terminado em `,99`.

### 12.5 Pix agendado

```
dia D-1:  app-api/console ── POST /v1/schedules ──▶ scheduler: valida conta, data, CPF do pagador
dia D 06:00:  ExecutionJob → tentativa 1, chave sched-<exec>-1 → POST /v1/pix/payments → 202 → PROCESSING
          pix-service ... (fluxo 12.3) ... evento PIX_SENT_COMPLETED (requestId = sched-<exec>-1)
          SNS pix-payment-events ──▶ fila wallet-scheduler-pix-events ──▶ PixEventsListener
          execução EXECUTED → agendamento COMPLETED
Se 06:00 deu INSUFFICIENT_FUNDS: nova tentativa às 12:00 e às 18:00; depois, FAILED com o motivo.
```

---

## 13. As decisões que valem para todos os projetos

| Decisão | Por quê | Onde está escrito |
|---|---|---|
| Monorepo, mas cada produto com build, banco, CI e tag próprios | Entregar um produto sem arrastar os outros | ADR-009 do core |
| Só o core mexe em saldo | Garantias de dinheiro num lugar só | ADR-001 do pix, ADR-001 do scheduler |
| Hexagonal + ArchUnit | Regras testáveis sem infraestrutura; fronteiras verificadas no build | ADR-006 do core |
| SQL explícito com `JdbcClient`, sem JPA | Concorrência e locks precisam ser visíveis no código | ADR-002/003 do core |
| `Idempotency-Key` em todo pedido que move dinheiro | Repetir depois de timeout é seguro | READMEs |
| Outbox para toda mensagem | Nada publicado sem estar gravado, nada gravado sem ser publicado | ADR-002 do core, ADR-001 do pix |
| Consumidor deduplica pelo id da mensagem | O SQS entrega pelo menos uma vez | ADR-001 do pix |
| Tenant sempre vem do token | Um tenant não acessa outro trocando um id | ADR-005 do core |
| Escopos pequenos por finalidade (`pix:send` só debita e estorna) | Um token vazado faz o mínimo de estrago | ADR-010 do core |
| Dinheiro em centavos (`long`), nunca `double` | Ponto flutuante não representa centavos exatamente | `Money` |
| Duas roles por banco (owner × app) | A aplicação não consegue alterar a estrutura nem apagar o ledger | READMEs |
| Eventos sem CPF nem nome | Eventos saem do perímetro do banco | ADR-007 do core |
| JSON espelhando ISO 20022, e não XML, no barramento local | Legível e fácil de depurar; o XML fica num adaptador de borda | ADR-001 do pix |
| Fakes em memória em vez de mocks | O teste verifica comportamento, e não a ordem das chamadas | seção 16 |
| Nomes no código em inglês; textos para o usuário em português | Padrão de mercado no código; o cliente lê português | — |

---

## 14. Observabilidade: achando o que aconteceu

Todos os backends rodam com o **agente OpenTelemetry** (`-javaagent`) e mandam tudo para o
`otel-collector` do compose do core, que distribui:

| Sinal | Para onde | Como olhar |
|---|---|---|
| Traces | Jaeger | http://localhost:16686, buscar pelo serviço (`wallet-core`, `pix-service`, `wallet-scheduler`, `app-api-demo`…) |
| Logs | Loki | Grafana → Explore → Loki, ou o dashboard de logs |
| Métricas | Prometheus (scrape de `/actuator/prometheus`) | Grafana, pasta "Wallet Core": negócio, serviço, logs e Pix |

**Achar tudo de um tenant:** os logs levam `tenant_id` no MDC (no core por um interceptor; no
scheduler também no job e no listener, que rodam fora de uma requisição). No app-api, o MDC leva
`customer_id`, **nunca** CPF, e-mail, código ou token. Do log você pula para o trace pelo `trace_id`.

**Métricas que valem alerta:**

- `wallet_audit_inconsistencies_total`: o replay achou saldo divergente. É crítico.
- `pix_queue_messages{state}` com algo na DLQ: cada mensagem ali precisa de análise.
- `pix_outbox_pending` e `wallet_outbox_pending` crescendo: o relay parou.

---

## 15. Onde alterar: receitas de manutenção

**Novo endpoint no core**

1. Porta `in` + serviço em `wallet-application`.
2. Registrar o caso de uso em `UseCaseConfig`.
3. Controller + DTO em `ApiModels`.
4. **Rota e escopo em `SecurityConfig`.** Sem isso, a rota responde 403 (`denyAll` por padrão).

**Nova coluna ou tabela (qualquer serviço)**

- Crie uma migration **nova** (`V<n+1>__descricao.sql`). Nunca edite uma migration que já rodou.
- No core, inclua a policy de RLS se a tabela for por tenant.

**Novo escopo**

1. `Tenant.DEFAULT_SCOPES` (vale para tenants novos).
2. Uma migration que conceda o escopo aos tenants já existentes (veja `V3`, `V5`).
3. A regra em `SecurityConfig` do serviço que exige o escopo.

**Nova regra para enviar Pix** (limite noturno, por exemplo)

- Implemente `PaymentPolicy` em `wallet-pix/.../application/` e registre em `PixServiceConfig`.
  Ela roda **antes** de qualquer débito.

**Novo código de recusa do SPI**

- `RejectionReason` (pix-service).
- `FailureReasons` (scheduler), se o agendamento precisar de texto e de regra de retentativa.
- `transaction-labels.ts` / `schedule-labels.ts` (console).
- `CustomerMessages` (app-api).

**Novo consumidor de eventos do Pix** (por exemplo, notificações)

1. Uma fila nova assinando `pix-payment-events`, com DLQ, em `init-bus.sh` (e na infraestrutura
   real).
2. Um listener que deduplique pelo id da mensagem e copie só os campos do `PixEvent` que usa (como
   o scheduler faz), para não acoplar o build ao `pix-messages`.

**Novo tenant**

1. No core: provisionar (hoje via `DevDataSeeder`; não há API administrativa).
2. No wallet-pix: adicionar o ISPB em `pix.participants`.
3. No scheduler: as credenciais em `scheduler.tenants`.
4. No app: **uma nova instância** do `app-api` com o seu banco (copie o bloco `app-api-segundo` do
   `wallet-app/docker-compose.yml`), e o desktop apontando para ela.

**Nova tela no console**

- Componente standalone em `pages/`, rota lazy em `app.routes.ts`, chamada em `WalletApiService`,
  tipos em `models.ts`.

**Nova tela no app desktop**

1. Novo caso em `Screen` (`Navigator.kt`).
2. ViewModel + `@Composable` na pasta da área.
3. A chamada em `AppApiClient`.
4. O endpoint no `app-api` (controller → serviço → porta).
5. Os DTOs em `app-contract`.

**Mudar as janelas do agendador**

- `scheduler.windows.times` no `application.yml`. A regra de "último horário" vem da lista.

---

## 16. Testes, CI e release

| Projeto | Comando | O que roda |
|---|---|---|
| wallet-core | `mvn verify` | domínio, casos de uso com fakes, ArchUnit, concorrência real (200 movimentos simultâneos, RLS, imutabilidade do ledger) |
| wallet-pix | `mvn verify` | domínio, casos de uso, contrato JSON, ArchUnit, integração (8 entregas simultâneas do mesmo ACSC creditam uma vez) |
| wallet-scheduler | `mvn verify` | domínio, casos de uso, ArchUnit, integração com Postgres e LocalStack |
| wallet-console | `npm test -- --watch=false` | Vitest + jsdom, sem navegador |
| wallet-app | `./gradlew build` | app-api (fakes, integração, ArchUnit, isolamento entre tenants) e ViewModels do desktop com `MockEngine` e relógio virtual |

Os testes de integração usam **Testcontainers**: precisam do Docker rodando.

**Fakes, não mocks.** Um fake é uma implementação de verdade da porta, em memória
(`InMemoryFixture`, `FakeCore`, `FakeScheduler`). O teste verifica **o resultado** ("o saldo ficou
X", "o agendamento ficou FAILED"), e não "o método Y foi chamado". Você pode refatorar à vontade
sem quebrar testes que continuam corretos.

**CI** (`.github/workflows/`):

- um workflow por projeto (`ci.yml` do core, `ci-wallet-pix.yml`, `ci-wallet-scheduler.yml`,
  `ci-wallet-console.yml`, `ci-wallet-app.yml`, `ci-wallet-otp.yml`), cada um com filtro de pasta: só roda se o PR
  tocar aquele projeto;
- todos validam o título do PR em **Conventional Commits** (`feat(wallet-pix): ...`), que vira a
  mensagem do commit na `main` (squash merge).

**Release:** por tag com prefixo do projeto (ADR-009 do core). Hoje só o core tem o workflow:
`wallet-core-vX.Y.Z` dispara o `release.yml`, que builda, testa e publica a imagem no GHCR. Os
outros projetos já seguem a convenção de tag (`wallet-pix-vX.Y.Z`…), mas ainda não têm workflow de
release. Fluxo de branches em [`CONTRIBUTING.md`](CONTRIBUTING.md).

---

## 17. Problemas comuns e onde procurar

| Sintoma | Causa provável |
|---|---|
| `403` numa rota nova | Falta a regra em `SecurityConfig`, ou o token não tem o escopo (peça com `?scope=`) |
| `403` na seção de agendamentos | O tenant não tem `schedules:*`: rode as migrations do core (V5) |
| `409 IDEMPOTENCY_KEY_REUSED` | Mesma chave com corpo diferente. Gere a chave uma vez por operação, e não por tentativa |
| `*-db-init` com `Exited (0)` | Normal: o container cria o banco e termina |
| Serviço não acha `wallet-core` / `postgres` | O compose do core não está de pé (a rede `wallet-core_default` não existe) |
| Pix parado em `SENT` | Simulador reiniciado (o estado é em memória) ou o LocalStack caiu; olhe a fila e a DLQ |
| Mensagem na DLQ | O consumidor falhou 5 vezes. Leia o log do serviço pelo `trace_id` |
| Agendamento de Pix sem fechar | Fila `wallet-scheduler-pix-events` não existe (suba o LocalStack do wallet-pix depois de atualizar o `init-bus.sh`) ou o evento ainda está no visibility timeout |
| App desktop volta ao login sozinho | Token expirado (30 min), 5 min sem uso, ou o `app-api` foi reiniciado com outra chave/tenant |
| `Wallet.exe` não abre | Falta um módulo da JVM em `nativeDistributions.modules` |
| `app-api` não sobe | Falta `APP_SESSION_SECRET` |
| Teste de integração "pulado" ou falhando ao conectar | Docker não está rodando |
| `ArchitectureTest` falhou | Você importou framework no domínio/aplicação ou um adapter `in` usou um adapter `out` |
| LocalStack pede token | Use a imagem fixada `localstack/localstack:4.12` |

---

## 18. Roteiro de estudo

A ordem que mais ensina, do centro para as bordas:

1. **Dinheiro e concorrência (Java + SQL).**
   - `Money`, `LedgerTransaction` e `MoveMoneyService` no core; depois `JdbcAccountRepository.applyDelta`.
   - Estude `UPDATE ... RETURNING`, locks de linha e `ON CONFLICT`.
   - Rode o `WalletCoreConcurrencyTest`.
2. **Segurança.**
   - `SecurityConfig` e `TokenController` do core.
   - Pegue um token com curl e decodifique em jwt.io.
3. **Mensageria (Java).**
   - `SendPixService` e `ReceivePixService`, depois `OutboxRelay` e `SqsQueueConsumer`.
   - Faça um Pix pelo `pixdev.py` e acompanhe as mensagens em `GET /simulate/messages` e o trace
     no Jaeger.
4. **Kotlin no backend.**
   - `Schedule.kt` e `Execution.kt` (sealed, `when`, data class), depois `ExecutionService.kt` e
     `ExecutionJob.kt` (`SKIP LOCKED`).
   - Compare com o Java do pix-service.
5. **Angular.**
   - `auth.interceptor.ts`, `wallet-api.service.ts`, depois `pages/account` (signals).
   - Mude um rótulo e veja o teste falhar.
6. **Kotlin no desktop.**
   - `Navigator.kt` e `App.kt`, depois uma tela de pagamento (`ui/payments`) com o ViewModel e o
     teste dele.
   - Estude coroutines: `suspend`, `launch`, `StateFlow`, `runTest`.
7. **Arquitetura de produto.**
   - Leia as ADRs na ordem: core 001→010, pix 001, scheduler 001, console 001, app 001.
   - Cada uma tem "alternativas consideradas": é ali que se aprende a decidir.
