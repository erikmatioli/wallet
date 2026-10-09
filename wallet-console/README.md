# Wallet Console

Console operacional (microfrontend) do produto de carteira digital, usado pelo operador do tenant.
Opera o [`wallet-core`](../wallet-core/) (onboarding de clientes, lista e busca de contas, saldo e
extrato, depósito, saque, transferência e auditoria por replay do ledger) e os agendamentos do
[`wallet-scheduler`](../wallet-scheduler/).

| | |
|---|---|
| Framework | Angular 21 — componentes standalone, zoneless (sem Zone.js), signals |
| Testes | Vitest + jsdom (`ng test`), sem necessidade de navegador |
| Estilo | SCSS puro com design tokens (sem biblioteca de UI) |
| Servido por | nginx (imagem Docker), que também faz proxy reverso para a API |

## Telas

| Rota | Tela | Endpoints usados |
|---|---|---|
| `/login` | Login com client id / secret do tenant | `POST /v1/auth/token` (Basic → JWT) |
| `/home` | Lista todas as contas do tenant (paginada), busca uma por agência/conta/dígito ou por CPF/CNPJ, botão "+ Nova conta" | `GET /v1/accounts`, `GET /v1/accounts/lookup`, `GET /v1/accounts/findByTaxId` |
| `/onboard` | Cadastro de cliente + abertura da conta, com e-mail opcional (para onde o app do cliente manda o código de acesso) | `POST /v1/customers` |
| `/accounts/:id`, cabeçalho | E-mail do titular, com "cadastrar" ou "alterar" | `PUT /v1/customers/{id}/email` |
| `/accounts/:id` | Saldo, extrato paginado com filtro "Somente Pix" e detalhe de cada lançamento (com link para a conta contraparte em transferências), depósito, saque, transferência (por ID ou por agência/conta/dígito), auditoria | `GET /v1/accounts/{id}`, `/statement` (`product=PIX`), `POST /deposits`, `/withdrawals`, `POST /v1/transfers`, `GET /audit` |
| `/accounts/:id`, seção Agendamentos | Agendamentos que a conta vai pagar, com a execução e as tentativas de cada um; agendar transferência ou Pix; cancelar até a véspera | `GET /v1/schedules?payerAccountId=`, `POST /v1/schedules`, `POST /v1/schedules/{id}/cancel` (wallet-scheduler) |

## Rodando

Nenhum `docker-compose` do repositório sobe o console. Ele roda em desenvolvimento com `npm start`,
ou pela imagem Docker construída à mão.

### Em desenvolvimento (recomendado)

Pré-requisitos: Node 22+ e o wallet-core rodando em `localhost:8080`. A seção de agendamentos também
precisa do wallet-scheduler em `localhost:8082`; sem ele, só ela mostra erro.

```bash
(cd ../wallet-core && docker compose up -d)        # obrigatório
(cd ../wallet-scheduler && docker compose up -d)   # só para os agendamentos
npm ci
npm start          # http://localhost:4200, com proxy /v1/schedules → :8082 e /v1/** → :8080
```

```bash
npm test -- --watch=false   # testes unitários (Vitest + jsdom, sem navegador)
npm run build               # build de produção em dist/wallet-console/browser
```

Login de desenvolvimento: `demo-tenant` / `demo-secret-change-me-please` (criado pelo perfil `dev` do wallet-core).

### Pela imagem Docker

A imagem serve o build com nginx, que faz o mesmo proxy para `wallet-core:8080` e
`wallet-scheduler:8082`. Por isso o container entra na rede do compose do wallet-core:

```bash
docker build -t wallet-console:local .
docker run --rm -p 4200:80 --network wallet-core_default wallet-console:local   # http://localhost:4200
```

### Depurando

- **No navegador:** com `npm start`, o DevTools do Chrome mostra o TypeScript original (Sources, Ctrl+P),
  as chamadas `/v1/...` com seus cabeçalhos (Network) e a sessão em Session Storage, chave
  `wallet-console.session`. No VS Code, a configuração **ng serve** de `.vscode/launch.json` faz o mesmo
  de dentro do editor.
- **Nos testes:** rode `npm test` num *JavaScript Debug Terminal* do VS Code; ele se liga ao processo
  Node do Vitest e para nos breakpoints dos `.spec.ts` e do código testado.

## Como está organizado

```
src/app/
├── core/       serviços sem UI: AuthService, interceptor Bearer, guard de rota,
│               WalletApiService (cliente tipado da API), models.ts (espelho dos DTOs do backend)
├── pages/      uma pasta por rota: login, home, onboard, account (todas com lazy loading);
│               account/ tem ainda entry-detail/ e schedules/
└── shared/     componentes reutilizáveis (problem-banner)
```

`core/models.ts` espelha o `ApiModels.java` do wallet-core: se o contrato da API mudar, é o primeiro arquivo a atualizar.

## Decisões de projeto

Registradas em [`docs/adr/001-arquitetura-do-console.md`](docs/adr/001-arquitetura-do-console.md). Em resumo:

- **Chamadas sempre relativas (`/v1/...`)**, nunca a URL do backend embutida no código: em dev o
  `proxy.conf.json` encaminha para o backend, em produção o nginx faz o mesmo. Mesma origem = **zero
  configuração de CORS** no wallet-core.
- **Sessão em `sessionStorage`** (some ao fechar a aba), com o JWT anexado por um interceptor.
- **Cada operação financeira gera uma `Idempotency-Key` nova** (`crypto.randomUUID()`), como o backend exige.
- **Ainda não federado**: ver ADR-001, seção "Microfrontend".

## Versão do Angular

Gerado com Angular CLI **21.2** (linha estável, com suporte). O Angular 22 já saiu, mas seu CLI exige um patch de
Node mais novo que o do ambiente onde este projeto foi criado. Para atualizar na sua máquina (Node ≥ 22.22.3):
`ng update @angular/core@22 @angular/cli@22`.
