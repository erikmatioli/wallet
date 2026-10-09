# ADR-001 (wallet-otp) — Motor de códigos de uso único (OTP)

**Status:** proposta

## Contexto
O wallet-app vai trocar a senha por um código de uso único enviado por e-mail (ADR-002 do
wallet-app): no cadastro, o código prova que o cliente tem a posse do e-mail; em cada login, o
código é a senha. Outros usos já estão no horizonte, como confirmar um pagamento de valor alto
antes de executá-lo.

Um e-mail pode servir a várias contas (o mesmo e-mail para o CPF de uma pessoa e o CNPJ da empresa
dela), então o código não pode identificar o e-mail: ele identifica **quem** está sendo verificado
(o CPF/CNPJ) e **para quê**.

## Decisões

### 1. Serviço próprio no monorepo, em Kotlin
O `wallet-otp` é outro projeto do monorepo, como o wallet-scheduler: outro deploy, outro banco
(`otp`), workflow de CI próprio com filtro de pasta e tags `wallet-otp-vX.Y.Z`. Mesma base técnica
do scheduler: Kotlin, Maven, Spring Boot 4, `JdbcClient`, Flyway, fakes em memória nos testes,
Testcontainers, ArchUnit e o agente OpenTelemetry. Porta local **8085**.

Um serviço, e não um módulo dentro do app-api, porque o motor vai ser usado por mais de um produto:
limites de envio, auditoria e regras de expiração ficam num lugar só.

### 2. O motor não conhece clientes nem e-mails cadastrados
Quem chama (o app-api, por exemplo) já sabe quem é o cliente e qual é o e-mail dele. O motor recebe,
a cada pedido, o **sujeito** (CPF/CNPJ), a **finalidade** e o **destino** (o e-mail), gera o código,
envia e depois confere. Ele não decide se o sujeito existe nem guarda vínculo entre e-mail e sujeito.

- **Finalidades:** `SIGNUP`, `LOGIN` e `PAYMENT_APPROVAL` (reservada para depois). Um código de uma
  finalidade nunca vale para outra.
- **Contexto opcional:** quem chama pode mandar um texto que descreve o que está sendo aprovado. O
  motor guarda só o hash e exige o mesmo contexto na conferência. No cadastro, o contexto é o e-mail
  informado (o código não confirma outro e-mail). Num pagamento, será valor + recebedor + conta de
  origem: o código que aprovou R$ 10 mil para X não aprova R$ 10 mil para Y.
- **Tenant:** vem do token, como em todos os serviços. Os desafios de um tenant não existem para outro.

### 3. API
Aceita os JWTs do wallet-core, sem outro provedor de identidade. Escopo novo `otp:use`, dado a todos
os tenants por uma migration do core.

| Método e rota | O que faz |
|---|---|
| `POST /v1/otp/challenges` | Cria um desafio e envia o código. Corpo: `subject`, `purpose`, `channel` (`EMAIL`), `destination`, `context` (opcional). Responde `201` com `challengeId`, `expiresAt` e o destino mascarado (`e***@gmail.com`) |
| `POST /v1/otp/challenges/{id}/verify` | Confere. Corpo: `code`, `subject`, `context` (se houve). `200 {verified: true}` uma única vez; recusas com `code` estável |

Recusas: `INVALID_CODE` (com as tentativas que restam), `CHALLENGE_EXPIRED`, `CHALLENGE_LOCKED`,
`CHALLENGE_ALREADY_USED`, `CHALLENGE_NOT_FOUND` (inclusive quando o sujeito ou o contexto não batem,
para não dizer qual dos dois errou), `TOO_MANY_REQUESTS` na criação.

### 4. Regras do código
- **6 dígitos**, gerados com `SecureRandom`.
- **Validade de 5 minutos** e **uso único**: depois de conferido com sucesso, o desafio fica `USED`.
- **5 tentativas erradas** e o desafio fica `LOCKED`; é preciso pedir outro.
- **Só o hash é guardado:** HMAC-SHA256 com uma chave do serviço (`OTP_CODE_KEY`, sem valor padrão,
  como a chave de sessão do app-api). Um SHA-256 simples de 6 dígitos se quebra testando um milhão
  de valores; com a chave, quem tem só o banco não consegue.
- **Comparação em tempo constante** (`MessageDigest.isEqual`).
- **Destino guardado só mascarado:** o e-mail completo é usado para enviar e descartado.
- **Pedir um código novo** para o mesmo sujeito e finalidade invalida o anterior que ainda estava
  aberto: vale sempre o último que o cliente recebeu.

### 5. Limites de envio
Para ninguém usar o serviço para lotar a caixa de alguém nem gastar o provedor de e-mail:
- por sujeito e finalidade: **1 código a cada 60 s** e **5 por hora**;
- por destino: **10 por hora**, somando todos os sujeitos (o caso do e-mail de várias contas);
- acima disso, `429 TOO_MANY_REQUESTS` com o tempo de espera (`retryAfterSeconds`).

Os limites contam os desafios no banco, então valem com mais de uma instância.

### 6. Envio por e-mail atrás de uma porta
A aplicação fala com uma porta `CodeSender`. O adaptador é SMTP. No ambiente local, o compose sobe o
**Mailpit**: recebe tudo o que é enviado e mostra numa página web (`http://localhost:8025`), sem nada
sair da máquina. Outro canal (SMS) seria outro adaptador.

O envio acontece **depois** de o desafio ser gravado. Se o envio falhar, o desafio é marcado
`FAILED` e a criação responde `503`: o cliente pede de novo, e a falha não conta no limite.

### 7. Observabilidade
Métricas por finalidade e resultado (`otp_challenges_total`, `otp_verifications_total`), o agente
OpenTelemetry como nos outros serviços, e logs com o `tenant_id`. Nunca o código, o CPF completo ou
o e-mail completo em log.

## Ajustes feitos na implementação

- **Código substituído:** pedir um código novo deixa o anterior `SUPERSEDED`, e conferi-lo responde
  `CHALLENGE_SUPERSEDED` ("use o código mais novo"), em vez de "vencido".
- **E-mail que não saiu:** o desafio `FAILED` responde `CHALLENGE_NOT_FOUND` na conferência: o cliente
  nunca recebeu aquele código.
- **Corrida na criação:** dois pedidos ao mesmo tempo são serializados por *advisory locks* do PostgreSQL
  (`pg_advisory_xact_lock`), sempre na ordem destino e depois sujeito, para nunca se esperarem em ciclo.
  Um índice único parcial garante no banco que só existe um desafio `OPEN` por sujeito e finalidade.
- **Saúde:** o health check não depende do servidor de e-mail; com ele fora, a criação responde `503` e a
  conferência continua funcionando.
- **Tempo de espera exato:** o `429` informa quando sai da janela de uma hora o código mais antigo, não uma
  hora fixa.

## Plano por módulo

### wallet-otp (novo)
1. **Esqueleto:** `pom.xml`, aplicação com health check, ArchUnit, Dockerfile, `docker-compose.yml`
   na rede do wallet-core com o banco `otp` e o Mailpit.
2. **Domínio e aplicação:** desafio e suas transições, geração e hash do código, regras de validade,
   tentativas e limites; portas para o repositório, o envio e o relógio.
3. **Adaptadores:** API REST, JDBC, SMTP; migrations.
4. **Testes:** unitários com relógio controlado; integração com Postgres (Testcontainers) e um
   servidor SMTP falso.
5. **CI e docs:** `ci-wallet-otp.yml`, README.

### wallet-core
- Escopo `otp:use` em `Tenant.DEFAULT_SCOPES` e numa migration.

## Consequências
- **Positivas:** nenhuma senha guardada em lugar nenhum; posse do e-mail como prova no cadastro; o
  mesmo motor serve para aprovar pagamentos depois, com o código preso ao conteúdo do pagamento.
- **Negativas:** mais um serviço e banco. A segurança da conta passa a ser a do e-mail do cliente. Se
  o envio de e-mail cair, ninguém entra no app.
- **Fora do escopo:** SMS, aplicativo autenticador (TOTP), lembrar o dispositivo, notificar o cliente
  de logins novos.
