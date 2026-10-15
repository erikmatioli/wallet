# Wallet Console

Console operacional (microfrontend) do produto de carteira digital, usado pelo operador do tenant.
Opera o [`wallet-core`](../wallet-core/) (onboarding de clientes, lista e busca de contas, saldo e
extrato, depósito, saque, transferência, auditoria por replay do ledger, bloqueios e encerramento da
conta, tesouraria), os agendamentos do [`wallet-scheduler`](../wallet-scheduler/) e a cesta de tarifas
do [`wallet-fees`](../wallet-fees/).

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
| `/home` | Lista todas as contas do tenant (paginada), com o CPF/CNPJ completo do titular (escopo `customers:document`; sem ele, mascarado), busca uma por agência/conta/dígito ou por CPF/CNPJ, botão "+ Nova conta" | `GET /v1/accounts`, `GET /v1/accounts/lookup`, `GET /v1/accounts/findByTaxId` |
| `/onboard` | Cadastro de cliente + abertura da conta, com e-mail opcional (para onde o app do cliente manda o código de acesso) | `POST /v1/customers` |
| `/accounts/:id`, cabeçalho | CPF/CNPJ completo do titular, com "copiar" (só os números); e-mail do titular, com "cadastrar" ou "alterar" | `PUT /v1/customers/{id}/email` |
| `/accounts/:id` | Saldo, extrato paginado com filtro "Somente Pix" e detalhe de cada lançamento (com link para a conta contraparte em transferências), depósito, saque, transferência (por ID ou por agência/conta/dígito), auditoria | `GET /v1/accounts/{id}`, `/statement` (`product=PIX`), `POST /deposits`, `/withdrawals`, `POST /v1/transfers`, `GET /audit` |
| `/accounts/:id`, seção Agendamentos | Agendamentos que a conta vai pagar, com a execução e as tentativas de cada um; agendar transferência ou Pix; cancelar até a véspera | `GET /v1/schedules?payerAccountId=`, `POST /v1/schedules`, `POST /v1/schedules/{id}/cancel` (wallet-scheduler) |
| `/accounts/:id`, seções Bloqueios e encerramento / Histórico da conta | Bloqueios ativos e liberação; bloquear por fraude, bloquear tudo, bloqueio judicial, transferência judicial e encerramento com lista de verificação (cada ação só aparece para o escopo que a permite); o histórico de abertura, bloqueios, liberações e encerramento (ADR-013 do core) | `POST /v1/accounts/{id}/blocks`, `/blocks/{id}/release`, `/judicial-transfers`, `/close`, `GET /timeline` |
| `/accounts/:id`, seção Pacote e tarifas (com `fees:read`) | Pacote da conta e migração para outro do mesmo tipo (com `fees:admin`), franquia usada no mês com o preço depois dela, últimas tarifas com a situação e o motivo de uma pendência, mudanças de pacote. O bloqueio de tarifa aparece em Bloqueios, sem "Liberar": o wallet-fees o libera ao cobrar | `GET /v1/fees/accounts/{id}`, `PUT /v1/fees/accounts/{id}/package`, `GET /v1/fees/packages` (wallet-fees) |
| `/tarifas` (com `fees:read`) | Os seis pacotes (PF e PJ) com a tabela em vigor, a próxima e o histórico; nova tabela a partir de uma data, com mensalidade, franquia e preço por operação (com `fees:admin`) | `GET /v1/fees/packages`, `GET/POST /v1/fees/packages/{id}/schedules` (wallet-fees) |
| `/tesouraria` (com `ledger:audit`) | Contas de lastro com o saldo esperado, o dinheiro descoberto e o extrato de cada uma; cadastro, cobertura e recomposição (com `treasury:write`); depósitos judiciais a transferir e o registro da TED; receitas do tenant (disponível, faturado e retirado no mês, extrato) e a retirada da receita para uma conta do tenant (com `treasury:write`) (ADR-011, ADR-013 e ADR-015 do core) | `GET/POST /v1/treasury/**`, `GET /v1/judicial-transit/pending`, `POST /v1/judicial-transit/{id}/teds` |

## Rodando

Nenhum `docker-compose` do repositório sobe o console. Ele roda em desenvolvimento com `npm start`,
ou pela imagem Docker construída à mão.

### Em desenvolvimento (recomendado)

Pré-requisitos: Node 22+ e o wallet-core rodando em `localhost:8080`. A seção de agendamentos também
precisa do wallet-scheduler em `localhost:8082`, e as tarifas, do wallet-fees em `localhost:8088`; sem
eles, só essas partes mostram erro.

```bash
(cd ../wallet-core && docker compose up -d)        # obrigatório
(cd ../wallet-scheduler && docker compose up -d)   # só para os agendamentos
(cd ../wallet-fees && docker compose up -d)        # só para as tarifas
npm ci
npm start          # http://localhost:4200, com proxy /v1/schedules → :8082, /v1/fees → :8088 e /v1/** → :8080
```

```bash
npm test -- --watch=false   # testes unitários (Vitest + jsdom, sem navegador)
npm run build               # build de produção em dist/wallet-console/browser
```

Login de desenvolvimento: `demo-tenant` / `demo-secret-change-me-please` (criado pelo perfil `dev` do wallet-core).

### Pela imagem Docker

A imagem serve o build com nginx, que faz o mesmo proxy para `wallet-core:8080`,
`wallet-scheduler:8082` e `wallet-fees:8088`. Por isso o container entra na rede do compose do wallet-core:

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
