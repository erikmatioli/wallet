# wallet-app

App **desktop** do cliente final, em Kotlin com Compose, e seu backend (`app-api`). O cliente abre a
própria conta e movimenta só ela: saldo, extrato, transferência, Pix e agendamentos. As decisões
estão na [ADR-001](docs/adr/001-app-do-cliente.md).

## Módulos (um build Gradle)

| Módulo | O que é |
|---|---|
| `app-contract` | DTOs da API do app (`@Serializable`), usados pelo `app-api` e pelo desktop |
| `app-api` | BFF: Spring Boot 4 + Kotlin, login do cliente, chama o wallet-core, o wallet-pix e o wallet-scheduler como o tenant |
| `app-desktop` | Compose Multiplatform for Desktop: telas, ViewModels com `StateFlow`, cliente HTTP Ktor |

É o primeiro projeto do repositório em **Gradle**: o plugin do Compose só existe para Gradle. As
versões ficam em `gradle/libs.versions.toml`; o Kotlin é o mesmo que o Spring Boot traz (2.3).

## Telas

```
Login ──► Abrir conta
  │
  ▼
Início (saldo, últimos lançamentos)
  ├── Extrato ──► Detalhe do lançamento
  ├── Transferir ──► Confirmar ──► Comprovante
  ├── Pix ──► Confirmar ──► Comprovante (acompanha até concluir)
  └── Agendamentos ──► Detalhe (tentativas, motivo, cancelar)
                   └── Novo agendamento ──► Confirmar
```

**Cliente que já existia no core** (aberto pelo console): não se cadastra no app, entra direto pelo
login com o **CPF, só números, como senha**. Provisório: ver ADR-001, decisão 4.

A sessão fica só em memória e acaba depois de 30 minutos, ou de 5 minutos sem usar o app.

## Rodar localmente

```bash
cd ../wallet-core && docker compose up -d        # Postgres, observabilidade e wallet-core
cd ../wallet-pix && docker compose up -d         # para Pix
cd ../wallet-scheduler && docker compose up -d   # para agendamentos
cd ../wallet-app && docker compose up -d --build # app-api na porta 8083
./gradlew :app-desktop:run                       # abre a janela do app
```

O desktop procura o `app-api` em `http://localhost:8083/`; para outro endereço, use
`-Dwallet.api.url=...` ou a variável `WALLET_APP_API_URL`.

**Um app por tenant.** Cada `app-api` atende **um** tenant (`APP_TENANT_CLIENT_ID` /
`APP_TENANT_CLIENT_SECRET`), com banco de logins e chave de sessão (`APP_SESSION_SECRET`, sem valor
padrão) próprios. O compose sobe os dois tenants do perfil `dev` do wallet-core:

| Tenant | `app-api` | Banco | Desktop |
|---|---|---|---|
| `demo-tenant` | `localhost:8083` | `app` | `./gradlew :app-desktop:run` |
| `segundo-tenant` | `localhost:8084` | `app_segundo` | `WALLET_APP_API_URL=http://localhost:8084/ ./gradlew :app-desktop:run` |

Um cliente que abre conta num app é cliente daquele tenant, e o token dele só vale naquele
`app-api`. O título da janela mostra de qual tenant é o app.

**Dinheiro para testar:** o cliente não tem como pôr dinheiro na conta (não há cash-in). Use um
depósito pelo console ou um Pix recebido pelo simulador do SPI.

## Gerar o app para instalar

```bash
./gradlew :app-desktop:createDistributable   # app-desktop/build/compose/binaries/main/app/Wallet (com a JVM)
./gradlew :app-desktop:packageMsi            # instalador .msi (usa o WiX; opcional)
```

## Build e testes

```bash
./gradlew build     # os três módulos; os testes do app-api precisam de Docker (Testcontainers)
```

Os testes do desktop não abrem janela: os ViewModels são testados com um servidor HTTP falso
(`MockEngine` do Ktor), e o bloqueio por inatividade com o relógio virtual das coroutines.
