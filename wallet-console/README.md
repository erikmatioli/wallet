# Wallet Console

Console operacional (microfrontend) do produto de carteira digital. Opera as funcionalidades da
primeira versão do [`wallet-core`](../wallet-core/): onboarding de clientes, consulta de saldo e extrato,
depósito, saque, transferência e auditoria (replay do ledger).

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
| `/onboard` | Cadastro de cliente + abertura da conta | `POST /v1/customers` |
| `/accounts/:id` | Saldo, extrato paginado, depósito, saque, transferência (por ID ou por agência/conta/dígito), auditoria | `GET /v1/accounts/{id}`, `/statement`, `POST /deposits`, `/withdrawals`, `POST /v1/transfers`, `GET /audit` |

O wallet-core ainda não tem endpoint de listagem/busca de clientes ou contas — por isso a navegação
é "cadastrar → abrir a conta criada" (ou abrir `/accounts/<id>` direto, com o id em mãos).

## Rodando

### Tudo junto (recomendado)

Da raiz do repositório: `docker compose up --build` → console em http://localhost:4200.

### Só o console, em desenvolvimento

Pré-requisitos: Node 22+ e o wallet-core rodando em `localhost:8080`.

```bash
cd wallet-console
npm ci
npm start          # http://localhost:4200, com proxy /v1/** → localhost:8080
```

```bash
npm test -- --watch=false   # testes unitários
npm run build               # build de produção em dist/wallet-console/browser
```

Login de desenvolvimento: `demo-tenant` / `demo-secret-change-me-please` (criado pelo perfil `dev` do wallet-core).

## Como está organizado

```
src/app/
├── core/       serviços sem UI: AuthService, interceptor Bearer, guard de rota,
│               WalletApiService (cliente tipado da API), models.ts (espelho dos DTOs do backend)
├── pages/      uma pasta por rota: login, onboard, account (todas com lazy loading)
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
