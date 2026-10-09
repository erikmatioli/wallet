# M-Wall (wallet-mobile)

O app do **cliente final** para celular: um **PWA** (Progressive Web App) que se instala pela tela
inicial, sem loja. Faz o mesmo que o app desktop do [`wallet-app`](../wallet-app/), falando com o mesmo
backend, o `app-api`:

- abrir conta e entrar **sem senha**, com o código que chega por e-mail;
- saldo (que pode ser escondido), últimos lançamentos e extrato por dia, com detalhe;
- **Pix** para outra instituição e **transferência** entre contas, com confirmação e comprovante;
- **agendamentos**: lista, detalhe, cancelamento e novo agendamento (transferência ou Pix).

Decisões: [ADR-001](docs/adr/001-pwa-do-cliente.md).

## Identidade visual

| | |
|---|---|
| Nome | **M-Wall** |
| Marca | `public/brand/m-wall-mark.svg`: um "M" de traço arredondado sobre um quadrado laranja; a faixa clara no topo é a aba da carteira, e o ponto é uma moeda |
| Cores | laranja `#F26A1B` (marca), `#C9490A` (botões, texto branco com contraste AA), `#C2470A` (texto laranja), creme `#FFE2C7`; neutros quentes `#1E1612` / `#FAF7F4`. Tema escuro com `#15100D` e o laranja `#FF8A3D` |
| Fonte | Plus Jakarta Sans (variável), servida pelo próprio app |
| Ícones do app | `public/icons/` (192, 512, *maskable* e o do iPhone), gerados do SVG da marca |

Os tokens (cores, raios, sombras, claro e escuro) ficam em `src/styles.scss`, junto com as peças
comuns: botões, campos, cartões, listas, *chips* e a folha inferior.

## Rodando

O PWA precisa do `app-api` e do que ele usa. Suba antes, nesta ordem: `wallet-core`, `wallet-otp`,
`wallet-app` (e `wallet-pix` / `wallet-scheduler` para Pix e agendamentos). Veja o
[README da raiz](../README.md).

### Em desenvolvimento

```bash
npm ci
npm start            # http://localhost:4300, com /app/v1 encaminhado ao app-api do demo-tenant (8083)
npm test -- --watch=false
```

O `ng serve` não liga o service worker (só o build de produção liga). Para ver o app como no celular,
abra o DevTools e ligue a emulação de dispositivo.

### Pela imagem Docker

```bash
docker compose up -d --build
```

| Endereço | Tenant | `app-api` |
|---|---|---|
| http://localhost:8086 | `demo-tenant` | `app-api:8083` |
| http://localhost:8087 | `segundo-tenant` | `app-api-segundo:8083` |

A mesma imagem serve os dois: a variável `APP_API_UPSTREAM` diz para qual `app-api` o nginx encaminha
`/app/v1/**`.

### No celular

O service worker (e por isso a instalação) só funciona em **HTTPS** ou em `localhost`. Para testar a
instalação num celular de verdade, exponha a porta 8086 por um túnel HTTPS, ou use o redirecionamento
de portas do Chrome (`chrome://inspect` → *Port forwarding* → `8086` para `localhost:8086`) e abra
`http://localhost:8086` no celular. Por `http://<ip-da-máquina>:8086` o app funciona, mas não instala
nem guarda nada offline.

**Para entrar:** o CPF de um cliente cadastrado pelo app e o código que chega no Mailpit
(http://localhost:8025). Um cliente aberto pelo console também entra, se o operador cadastrou o e-mail
dele (ADR-003 do wallet-app).

### Depurando

No VS Code, a configuração **ng serve** (`.vscode/launch.json`) sobe o `npm start` e abre o Chrome
com breakpoints no TypeScript.

## Como está organizado

```
src/app/
├── core/          contrato do app-api em TypeScript, AppApi (HTTP), sessão, formatação, inatividade, PWA
├── flows/         o estado de cada fluxo (login, cadastro, início, extrato, Pix, transferência,
│                  agendamentos): classes com signals, sem DOM, testadas com uma API falsa
├── pages/         as telas; cada uma cria o seu flow e só desenha o estado
├── ui/            peças da identidade: marca, ícones, campo de valor, folha inferior, alerta, lançamento
└── testing/       a API falsa dos testes
```

## Decisões de projeto

- **Só o `app-api`, na mesma origem.** O nginx serve o app e encaminha `/app/v1`: sem CORS e sem URL
  de backend no bundle. A conta pagadora vem sempre do token do cliente.
- **O service worker guarda só o app**, nunca uma resposta de `/app/v1` (`ngsw-config.json` sem
  `dataGroups`, e `Cache-Control: no-store` no proxy).
- **Sessão só em memória.** Nada em `localStorage`, cookie ou IndexedDB; recarregar pede o código de
  novo. Cinco minutos sem uso encerram, também quando o app volta do segundo plano.
- **Pagamentos em três passos** (formulário, confirmação, comprovante). A `Idempotency-Key` nasce na
  confirmação e se repete numa nova tentativa. O comprovante do Pix acompanha o status por alguns
  segundos.
- **Valor digitado como em app de banco:** os dígitos preenchem os centavos da direita para a
  esquerda, no teclado numérico. Inteiros do começo ao fim, nunca ponto flutuante.
- **CSP restrita:** scripts só da própria origem; estilos inline liberados porque o Angular põe o CSS
  de cada componente numa tag `<style>`. Por isso o CSS crítico inline está desligado no `angular.json`.
- **O contrato é repetido** em `src/app/core/contract.ts`. O kotlinx.serialization omite campo com valor
  padrão: esses campos são opcionais aqui, e quem lê aplica o padrão.

## Limitações conhecidas

- Sem notificações push, biometria ou "lembrar de mim" (fora do escopo da ADR-001).
- No iPhone, a instalação é pelo menu Compartilhar → "Adicionar à Tela de Início"; o Safari não oferece
  o botão "Instalar o M-Wall".
