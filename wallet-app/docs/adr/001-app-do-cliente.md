# ADR-001 (wallet-app) — App desktop do cliente final e seu backend (BFF)

**Status:** proposta

## Contexto
Até aqui, quem opera a carteira é o **tenant**: o console é uma ferramenta interna, com login por
`clientId` e segredo, e um token de tenant enxerga todas as contas daquele tenant. Queremos um app
para o **cliente final**: ele abre a própria conta e movimenta só ela (saldo, extrato,
transferência, Pix e agendamentos), com navegação simples.

O app é desktop, em **Kotlin**, também com o objetivo de aprender como uma aplicação Kotlin desktop
funciona na prática.

Um app instalado na máquina do cliente não pode guardar o segredo do tenant: qualquer pessoa o
extrairia do binário e moveria dinheiro de todos os clientes do tenant.

## Decisões

### 1. Um BFF com login do cliente; o wallet-core não muda
Um serviço novo, o **`app-api`** (backend for frontend), é o único com quem o desktop fala:
- cadastra o cliente e guarda o **login por CPF e senha**;
- emite o token **do cliente**, assinado por ele, com o `customer_id` e a conta;
- chama o wallet-core, o wallet-pix e o wallet-scheduler **como o tenant** dono do produto, sempre
  restrito à conta do cliente logado.

O wallet-core continua B2B: o app do cliente é o produto do tenant, e o backend do produto é do
tenant. O desktop nunca vê um token do wallet-core.

**Regra que não pode quebrar:** o `app-api` nunca recebe do desktop o id da conta pagadora. A conta
vem sempre do token do cliente. É o equivalente, para cliente, do isolamento por tenant.

### 2. Um projeto Gradle com três módulos
O projeto `wallet-app` entra no monorepo (ADR-009 do wallet-core): CI própria e tags
`wallet-app-vX.Y.Z`. É o primeiro projeto em **Gradle**, porque o plugin do Compose só existe para
Gradle; o BFF vai junto, no mesmo build:

```
wallet-app/
├── app-contract   DTOs da API do app (kotlinx.serialization), usados pelos dois lados
├── app-api        BFF: Spring Boot 4 + Kotlin, JdbcClient, Flyway, banco próprio "app"
└── app-desktop    Compose Multiplatform for Desktop
```

- **Contrato compartilhado:** o mesmo `@Serializable data class` é a resposta do BFF e o que o
  desktop lê. Uma mudança de contrato quebra a compilação dos dois lados, e não a execução.
- O **`app-api`** segue a arquitetura hexagonal dos outros serviços (pacotes `domain`,
  `application`, `adapter.in`, `adapter.out`, `config`, com ArchUnit), como o wallet-scheduler.

### 3. Desktop com Compose, navegação por estado
- **Compose Multiplatform for Desktop:** interface declarativa e reativa, o jeito idiomático de
  fazer interface em Kotlin hoje.
- **Fluxo de dados numa direção (MVVM):** tela → ViewModel com `StateFlow` → repositório → cliente
  HTTP (Ktor Client com kotlinx.serialization). Chamadas em **coroutines**, nunca na thread da
  interface.
- **Navegação simples:** uma `sealed interface Screen` e uma pilha de telas guardada em estado. Sem
  biblioteca de navegação no MVP: dá para ver tudo o que acontece.

```
Login ──► Abrir conta
  │
  ▼
Início (saldo, últimos lançamentos)
  ├── Extrato ──► Detalhe do lançamento
  ├── Transferir ──► Confirmar ──► Comprovante
  ├── Pix ──► Confirmar ──► Comprovante (status)
  └── Agendamentos ──► Detalhe / Cancelar
                   └── Novo agendamento ──► Confirmar
```

### 4. Abertura de conta e login
- **Abrir conta:** CPF, nome e senha. O `app-api` chama `POST /v1/customers` no core (cria o cliente
  e a conta) e só então grava o login, com a senha em hash (BCrypt, do `spring-security-crypto`).
- **CPF que já tem cliente no core e não tem login no app** (por exemplo, criado pelo console): o
  o cadastro é recusado e o cliente entra pelo login com o **CPF, só números, como senha
  provisória**. Nesse primeiro login o `app-api` confirma no core que o cliente existe e cria o
  login já ativo, ligado à conta que o core tem. Vale para quem o console abrir depois também.
  É um atalho deste momento: o CPF não é segredo, então quem souber o CPF de um cliente do console
  que ainda não entrou no app entra por ele. A substituição (código de verificação e troca
  obrigatória da senha no primeiro acesso) fica para depois do MVP.
- **Login:** CPF e senha. Erro sempre genérico ("CPF ou senha inválidos"), para não revelar quais
  CPFs têm conta. Após **5 erros seguidos, o login fica bloqueado por 15 minutos**.
- **KYC** (documento, selfie) fica fora do MVP.

### 5. Sessão
- Token de acesso do cliente, JWT assinado pelo `app-api`, válido por **30 minutos**. Fica **só em
  memória** no desktop. Ao expirar, o cliente entra de novo. Refresh token e "lembrar de mim" (que
  exigiria o cofre de credenciais do sistema operacional) ficam para depois.
- **Bloqueio por inatividade:** 5 minutos sem uso no desktop voltam para a tela de login e apagam o
  token da memória.

### 6. Dinheiro na interface
- Valores em **centavos (`Long`)** no app e no contrato, formatados em pt-BR (R$ 1.234,56). Nunca
  `Double`.
- **Tela de confirmação** antes de transferir, enviar Pix ou agendar.
- A **`Idempotency-Key`** nasce na tela de confirmação e é mantida em novas tentativas: um duplo
  clique ou um timeout não pagam duas vezes. O `app-api` repassa a chave aos serviços.
- **Erros:** o `app-api` transforma o `code` dos serviços em mensagem para o cliente (saldo
  insuficiente, conta não encontrada, CPF do recebedor inválido...). O desktop mostra a mensagem.

### 7. API do app (`/app/v1`)
Tudo, exceto cadastro e login, exige o token do cliente e age só sobre a conta dele.

| Endpoint | O que faz | Chama |
|---|---|---|
| `POST /signup` | abre a conta e cria o login | core `POST /v1/customers` |
| `POST /login` | devolve o token do cliente | (banco do app) |
| `GET /me` | nome, conta formatada e saldo | core `GET /v1/accounts/{id}` |
| `GET /statement` | extrato paginado (com o bloco `pix`) | core `GET /v1/accounts/{id}/statement` |
| `POST /transfers` | transferência interna por agência/conta/dígito | core `POST /v1/transfers` |
| `POST /pix` | envia um Pix (o CPF do pagador vem do login) | wallet-pix `POST /v1/pix/payments` |
| `GET /pix/{endToEndId}` | status de um Pix enviado | wallet-pix `GET /v1/pix/payments/{id}` |
| `GET /schedules`, `POST /schedules`, `POST /schedules/{id}/cancel` | agendamentos da conta | wallet-scheduler |

### 8. Dados e segurança do `app-api`
- Banco próprio, `app`, com a tabela de login: CPF (único), hash da senha, `customer_id`,
  `account_id`, tentativas erradas, bloqueio até, criação.
- Chave de assinatura dos tokens do cliente separada da do wallet-core. Os dois tipos de token nunca
  se confundem: o `app-api` só aceita os seus.
- Credenciais do tenant só no `app-api`, por configuração.
- Logs com `customer_id` no MDC, sem CPF, sem senha e sem token.

### 9. Testes
- **`app-api`:** unitários com fakes; integração com Postgres, incluindo **um cliente nunca
  enxergar nem movimentar a conta de outro** e o bloqueio após 5 erros de senha.
- **Desktop:** ViewModels testados sem interface, com um fake da API; teste de UI do Compose no fluxo
  de transferência (formulário → confirmação → comprovante).

### 10. Dinheiro para testar
O cliente final não tem como pôr dinheiro na conta: não existe cash-in. Para testar, deposita-se
pelo console ou recebe-se um Pix pelo simulador do SPI (`POST /simulate/incoming`).

## Ajustes feitos na implementação

- **Cadastro que se recupera sozinho:** o login nasce `PENDING` antes de o core criar o cliente e
  só fica `ACTIVE` depois. Se o `app-api` cair no meio, o próximo cadastro com o mesmo CPF encontra
  o login pendente, recebe "cliente já existe" do core e recupera a conta pelo CPF, sem criar uma
  segunda. Um "cliente já existe" **sem** login pendente é um cliente criado fora do app: o
  cadastro é recusado (`CUSTOMER_EXISTS_OUTSIDE_APP`) e a mensagem manda entrar pelo login com o
  CPF como senha, como na decisão 4.
- **Primeiro acesso de cliente do console:** o login só consulta o core quando a senha digitada é
  o próprio CPF e não há login para ele, então senha errada de CPF desconhecido não custa chamada.
  A senha provisória fica guardada em hash, como qualquer outra, e o bloqueio por 5 erros vale igual.
- **Login sem pistas:** a mesma mensagem e o mesmo tempo de resposta para CPF desconhecido e senha
  errada (o `app-api` confere a senha contra um hash BCrypt de verdade mesmo sem login).
- **Chave de idempotência por cliente:** o wallet-core, o wallet-pix e o wallet-scheduler guardam
  as chaves **por tenant**, e todos os clientes do app são o mesmo tenant. A chave enviada adiante é
  `app-` + 40 caracteres do SHA-256 de `login:chave do desktop`: a mesma tentativa repetida gera a
  mesma chave, dois clientes nunca colidem, cabe nos 64 caracteres do wallet-pix e não leva o id do
  login para os outros serviços (`IdempotencyKeys`).
- **Isolamento entre clientes do mesmo tenant:** o wallet-pix e o wallet-scheduler só separam
  tenants. Para o Pix, o `app-api` guarda quem enviou cada um (tabela `sent_pix`) e só esse cliente
  consulta o status. Para os agendamentos, confere se a conta pagadora é a do token antes de
  detalhar ou cancelar.
- **"Pode cancelar?"** é decidido no `app-api` pela data em Brasília, com a mesma regra do
  agendador; o desktop não depende do relógio da máquina do cliente.
- **Mensagens:** os códigos de todos os serviços viram texto para o cliente num lugar só
  (`CustomerMessages`); um código desconhecido mantém a mensagem do próprio serviço.
- **Bloqueio por inatividade:** qualquer evento de mouse ou teclado na janela renova o tempo (sem
  consumir o evento); 5 minutos parado encerram a sessão e voltam ao login com o aviso.
- **Testes de coroutines:** as chamadas do Ktor rodam nas threads do motor HTTP, fora do relógio
  virtual do teste; os ViewModels devolvem o `Job` e os testes esperam por ele. Falhas dentro de
  `async` ficam presas num `coroutineScope { }` para não derrubar o escopo da janela.
- **Uma instância do `app-api` por tenant, com o tenant no token:** cada fintech tem o seu
  `app-api`, com as suas credenciais de tenant, o seu banco de logins (`app` para o `demo-tenant`,
  `app_segundo` para o `segundo-tenant`) e a sua chave de sessão, **sem valor padrão**: a instância
  não sobe sem `APP_SESSION_SECRET`. O token do cliente leva o claim `tenant` e o issuer
  `app-api:<tenant>`, e cada instância só aceita os seus. Duas instâncias que, por engano,
  compartilhem a chave continuam isoladas: o token de uma é recusado pela outra antes de qualquer
  chamada. O desktop mostra no título de qual tenant é o app (`/app/v1/info`). Um `app-api`
  multi-tenant (tenant por requisição, logins com `tenant_id`) foi considerado e descartado: mais
  barato de operar, mas cada ponto do código vira uma chance de misturar tenants.
- **Distribuição:** `gradlew :app-desktop:createDistributable` gera o app com a JVM embutida
  (cerca de 160 MB); o instalador `.msi` (`packageMsi`) usa o WiX e fica opcional. A JVM do pacote
  só leva os módulos declarados no build: faltando `java.net.http`, o app não abria (sem nenhuma
  mensagem, porque o executável não tem console); faltando `jdk.localedata`, o dinheiro aparecia em
  formato inglês ("356.50"). Os dois só apareceram rodando o executável, nunca no `gradlew run`.

## Ordem de entrega
1. **Esqueleto e riscos técnicos:** build Gradle com os três módulos; janela Compose abrindo; o
   `app-api` subindo com banco próprio; CI com Gradle. Valida antes de qualquer regra: versão do
   Compose compatível com o Kotlin do projeto, Gradle rodando na JVM 25, e o Spring Boot no Gradle.
2. **Cadastro, login, início e extrato:** `app-api` (signup, login, me, statement) e as telas.
3. **Transferência e Pix,** com confirmação e comprovante.
4. **Agendamentos.**
5. **Acabamento:** bloqueio por inatividade, mensagens de erro, empacotamento com instalador
   (opcional).

## Consequências
- **Positivas:** o segredo do tenant nunca sai do servidor; o core continua B2B sem conhecer o
  cliente final; o contrato compartilhado pega quebras na compilação; um app real para aprender
  Compose, coroutines e `StateFlow`.
- **Negativas:** mais um serviço com banco e credenciais; o primeiro projeto Gradle num repositório
  Maven (dois jeitos de construir para manter); o `app-api` repete o padrão "chamar como o tenant"
  que o wallet-pix e o wallet-scheduler já têm.
- **Fora do escopo:** KYC, refresh token e "lembrar de mim", ligar clientes existentes ao app,
  recuperação de senha, cash-in, notificações, outros sistemas operacionais além do usado no
  desenvolvimento (o Compose gera para Windows, macOS e Linux, mas só o Windows será testado).
