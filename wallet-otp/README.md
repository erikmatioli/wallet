# wallet-otp

Motor de códigos de uso único (OTP) enviados por e-mail. O wallet-app usa no cadastro (prova de posse do
e-mail) e em cada login (o código é a senha); outros produtos podem usar depois, por exemplo para aprovar
um pagamento de valor alto. Escrito em **Kotlin**, com a mesma arquitetura hexagonal dos outros
projetos. As decisões estão na [ADR-001](docs/adr/001-motor-de-otp.md).

## Como funciona

O motor não conhece clientes nem e-mails cadastrados. Quem chama informa, a cada pedido, **quem** está
sendo verificado (o CPF/CNPJ), **para quê** e **para onde** mandar. O motor gera o código, envia e depois
confere.

| Regra | Valor |
|---|---|
| Código | 6 dígitos, `SecureRandom` |
| Validade | 5 minutos, uso único |
| Tentativas | 5 erradas e o código fica bloqueado |
| Código novo | invalida o anterior do mesmo CPF e finalidade |
| Envios por CPF e finalidade | 1 a cada 60 s e 5 por hora |
| Envios por e-mail | 10 por hora, somando todos os CPFs que usam o mesmo e-mail |
| No banco | só o HMAC do código (chave `OTP_CODE_KEY`) e o e-mail mascarado |

- **Finalidades:** `SIGNUP`, `LOGIN` e `PAYMENT_APPROVAL` (reservada). Um código de uma finalidade nunca
  vale para outra.
- **Contexto:** quem chama pode mandar um texto que descreve o que está sendo aprovado, e o mesmo texto é
  exigido na conferência. O cadastro do app usa o e-mail; um pagamento usaria valor, recebedor e conta.
- **E-mail que não saiu:** o desafio fica `FAILED`, a resposta é `503 CODE_NOT_SENT`, e ele não conta nos
  limites.

## API

Aceita os JWTs do wallet-core com o escopo **`otp:use`**. O tenant vem do token, nunca do corpo.

| Método e rota | O que faz |
|---|---|
| `POST /v1/otp/challenges` | Cria o desafio e envia o código. Corpo: `subject`, `purpose`, `channel` (`EMAIL`, padrão), `destination`, `context` (opcional). `201` com `challengeId`, `expiresAt` e `destinationMasked` |
| `POST /v1/otp/challenges/{id}/verify` | Confere. Corpo: `code`, `subject`, `context` (se houve). `200 {"verified":true}` uma vez só |

| HTTP | `code` |
|---|---|
| 400 | `INVALID_SUBJECT`, `INVALID_DESTINATION`, `INVALID_PURPOSE`, `MALFORMED_REQUEST` |
| 404 | `CHALLENGE_NOT_FOUND`, também quando o CPF ou o contexto não batem (sem dizer qual) |
| 422 | `INVALID_CODE` (com `attemptsLeft`), `CHALLENGE_EXPIRED`, `CHALLENGE_LOCKED`, `CHALLENGE_ALREADY_USED`, `CHALLENGE_SUPERSEDED` |
| 429 | `TOO_MANY_REQUESTS`, com `retryAfterSeconds` e o cabeçalho `Retry-After` |
| 503 | `CODE_NOT_SENT` |

```bash
TOKEN=$(curl -s -u demo-tenant:demo-secret-change-me-please -X POST "localhost:8080/v1/auth/token?scope=otp:use" \
  | python -c "import sys,json;print(json.load(sys.stdin)['access_token'])")
curl -s -X POST localhost:8085/v1/otp/challenges -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"subject":"52998224725","purpose":"LOGIN","destination":"maria@example.com"}'
# o código aparece em http://localhost:8025
```

## Pacotes

```
br.com.walletotp
├── domain          Challenge (estados e tentativas), SendLimits, Subject, Email; só Kotlin/JDK
├── application     ChallengeService, Secrets (HMAC), portas; sem Spring
├── adapter.in      API REST
├── adapter.out     JDBC, SMTP, métricas Micrometer
└── config          ligação com o Spring
```

As dependências apontam sempre para dentro; o `ArchitectureTest` quebra o build se não apontarem.

## Rodar localmente

```bash
cd ../wallet-core && docker compose up -d     # Postgres, observabilidade e wallet-core
cd ../wallet-otp && docker compose up -d --build
curl localhost:8085/actuator/health           # {"status":"UP"}
```

O compose entra na rede do wallet-core, cria o banco `otp` com os papéis `otp_owner` (Flyway) e `otp_app`
(aplicação, sem DDL), e sobe o **Mailpit**: os e-mails enviados aparecem em http://localhost:8025, e nada
sai da máquina.

**Na IDE:** suba só o banco e o Mailpit (`docker compose up -d otp-db-init mailpit`) e rode
`WalletOtpApplication` com `OTP_CODE_KEY` definida (pelo menos 32 caracteres). Os outros padrões do
`application.yml` já apontam para `localhost`, e o Mailpit também escuta SMTP na porta 1025.

| Variável | Padrão | Para quê |
|---|---|---|
| `OTP_CODE_KEY` | nenhum (obrigatória) | Chave dos HMACs de códigos, contextos e destinos |
| `WALLET_CORE_URL` | `http://localhost:8080` | JWKS do wallet-core |
| `SMTP_HOST` / `SMTP_PORT` | `localhost` / `1025` | Servidor de e-mail |

## Observabilidade

`otp_challenges_total{purpose,result}` (`sent`, `limited`, `send_failed`) e
`otp_verifications_total{purpose,result}` (`verified`, `wrong_code`, `locked`, `expired`...) em
`/actuator/prometheus`. Os logs nunca têm o código, o CPF completo nem o e-mail completo.

## Build e testes

```bash
mvn verify      # unitários com relógio controlado, ArchUnit e integração com Postgres (Testcontainers)
```

O teste de integração prova, com o banco de verdade, que 6 pedidos simultâneos para o mesmo CPF enviam um
código só. Sem Docker, ele e o teste de subida são pulados.
