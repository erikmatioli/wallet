# Plataforma Wallet

Carteira digital **white label**: uma fintech (o **tenant**) contrata a plataforma e oferece contas de
pagamento aos seus clientes. Vários tenants usam a mesma instalação, e um nunca enxerga os dados do
outro.

Este repositório é um **monorepo** com seis produtos. Cada um tem build, banco, CI e documentação
próprios, e eles só conversam pelas APIs públicas e pelo barramento.

| Produto | O que faz | Stack | Porta local |
|---|---|---|---|
| [`wallet-core`](wallet-core/) | Clientes, contas, saldo, extrato e o **ledger** de partidas dobradas. Só ele mexe em saldo | Java 25, Spring Boot 4, Maven | 8080 |
| [`wallet-pix`](wallet-pix/) | Recebe e envia Pix conversando com o SPI (o sistema do Banco Central) por filas; inclui um simulador do SPI | Java 25, Spring Boot 4, Maven | 8081 (simulador 8090) |
| [`wallet-scheduler`](wallet-scheduler/) | Agenda transferências e Pix para uma data e executa no dia, com retentativa | Kotlin, Spring Boot 4, Maven | 8082 |
| [`wallet-otp`](wallet-otp/) | Códigos de uso único por e-mail: cadastro e login do app, aprovações no futuro | Kotlin, Spring Boot 4, Maven | 8085 (Mailpit 8025) |
| [`wallet-console`](wallet-console/) | Tela web **do operador** da fintech | TypeScript, Angular 21 | 4200 |
| [`wallet-app`](wallet-app/) | App desktop **do cliente final** e o seu backend (`app-api`) | Kotlin, Compose Desktop, Spring Boot 4, Gradle | 8083 / 8084 |

Quem chama quem:

- **operador** → `wallet-console` → `wallet-core` e `wallet-scheduler`
- **cliente** → app desktop → `app-api` → `wallet-core`, `wallet-pix`, `wallet-scheduler` e `wallet-otp`
- `wallet-pix` → `wallet-core` (débitos e créditos), e conversa com o SPI por filas (SNS/SQS)
- `wallet-scheduler` → `wallet-core` e `wallet-pix` no dia do pagamento, e ouve os eventos do Pix
- `wallet-pix`, `wallet-scheduler` e `wallet-otp` aceitam os JWTs do `wallet-core`, o único provedor de
  identidade dos tenants; o `app-api` emite um token próprio para o cliente final

## Subir na sua máquina

Pré-requisitos: Docker Desktop, JDK 25, Maven 3.9+ e Node 22+ (console). O Gradle do `wallet-app` vem
pelo wrapper.

Todos os composes entram na rede do `wallet-core` e usam o PostgreSQL dele, então **o core sobe
primeiro**. Os outros só são necessários para o que você for usar:

```bash
cd wallet-core       && docker compose up -d           # Postgres, wallet-core e observabilidade
cd ../wallet-pix     && docker compose up -d --build   # Pix: LocalStack, pix-service, simulador do SPI
cd ../wallet-scheduler && docker compose up -d --build # agendamentos
cd ../wallet-otp     && docker compose up -d --build   # códigos por e-mail e o Mailpit
cd ../wallet-app     && docker compose up -d --build   # app-api do demo-tenant (8083) e do segundo-tenant (8084)
cd ../wallet-console && npm ci && npm start            # console em http://localhost:4200
cd ../wallet-app     && ./gradlew :app-desktop:run     # app do cliente
```

| O quê | Endereço | Acesso de desenvolvimento |
|---|---|---|
| wallet-core | http://localhost:8080 | `demo-tenant` / `demo-secret-change-me-please` e `segundo-tenant` / `segundo-tenant-secret-please` (perfil `dev`) |
| Console | http://localhost:4200 | as credenciais do tenant |
| App do cliente | janela desktop | CPF + código que chega por e-mail |
| Mailpit | http://localhost:8025 | os e-mails com os códigos |
| Grafana / Jaeger / Prometheus | http://localhost:3000 / 16686 / 9090 | — |

Para parar sem perder dados: `docker compose stop` ou `docker compose down`, **sem `-v`**. O `-v` no
`wallet-core` apaga o volume do PostgreSQL, e com ele os bancos de todos os produtos.

## Documentação

- **[Guia do desenvolvedor](guia-do-desenvolvedor.md):** comece por aqui. Explica cada produto, como
  conversam, as decisões comuns, onde mexer para mudar algo e um roteiro de estudo.
- **README de cada produto:** API, configuração, como rodar e testar.
- **ADRs** em `<produto>/docs/adr/`: o registro de cada decisão, com as alternativas consideradas.
- **[CONTRIBUTING](CONTRIBUTING.md):** branches, Conventional Commits, PRs e releases.

## Convenções

- **Trunk-based:** `main` sempre testável; branches curtas (`feat/*`, `fix/*`, `docs/*`, `chore/*`).
- **Títulos de PR em Conventional Commits** (`feat(wallet-app): ...`), conferidos pelo CI.
- **Um workflow de CI por produto** em `.github/workflows/`, com filtro de pasta: um PR só roda o CI dos
  produtos que mudou.
- **Arquitetura hexagonal** nos serviços, cobrada por testes ArchUnit: as regras de negócio não dependem
  de framework.
- **O tenant vem sempre do token**, nunca do corpo da requisição; o wallet-core isola tenants com Row
  Level Security no PostgreSQL.
