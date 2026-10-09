# ADR-001 (wallet-mobile) — M-Wall, o app do cliente final como PWA

**Status:** proposta

## Contexto
O `wallet-app` entregou o app do cliente final para desktop (Compose) e o seu backend, o `app-api`
(BFF): cadastro e login por código no e-mail (ADR-002 do wallet-app), saldo, extrato, transferência,
Pix e agendamentos, sempre só na conta do cliente logado.

Queremos o mesmo produto **no celular**, sem loja de aplicativos: um **PWA** (Progressive Web App),
instalável pela tela inicial do celular e também usável no navegador do computador. O produto
ganha nome e identidade próprios: **M-Wall**, em tons de laranja.

## Decisões

### 1. Mesmo backend: o `app-api`, sem mudanças
O PWA fala só com o `app-api`, pelo mesmo contrato `/app/v1` do desktop. Nenhum serviço novo,
nenhum endpoint novo: o PWA é um segundo cliente do mesmo BFF. Valem as mesmas regras:
- a conta pagadora vem sempre do token do cliente, nunca do PWA;
- o PWA nunca vê um token do wallet-core nem o segredo do tenant.

### 2. Angular 21, como o wallet-console
- **Angular 21** com componentes standalone, **zoneless** e **signals**; testes com **Vitest**
  (o mesmo `@angular/build:unit-test` do console). Uma stack de front só no monorepo.
- **PWA** com o `@angular/service-worker`: manifesto, ícones e instalação.
- Sem biblioteca de componentes: a identidade do M-Wall é um pequeno design system próprio
  (tokens em CSS e poucos componentes), leve para o celular.
- Projeto `wallet-mobile/` no monorepo (ADR-009 do wallet-core), com CI própria e tags
  `wallet-mobile-vX.Y.Z`.

### 3. Mesma origem: nginx na frente do `app-api`
O PWA é servido por um nginx que também encaminha `/app/v1/**` para o `app-api`, como o console faz
com o wallet-core:
- o navegador fala só com uma origem: sem CORS no `app-api` e sem URL de backend dentro do bundle;
- **um PWA por tenant**, como o `app-api` (ADR-001 do wallet-app, decisão 1): o mesmo build, com o
  upstream escolhido por variável de ambiente (`APP_API_UPSTREAM`). Localmente, `8086` é o app do
  `demo-tenant` (→ `app-api` 8083) e `8087` o do `segundo-tenant` (→ `app-api-segundo`).
- cabeçalhos de segurança no nginx: CSP com scripts só de `'self'` (estilos inline liberados, porque o
  Angular põe o CSS de cada componente numa tag `<style>`), `frame-ancestors 'none'`,
  `Referrer-Policy: no-referrer`, `X-Content-Type-Options: nosniff`.

### 4. O service worker nunca guarda dados da conta
- Cache só do **app shell** (HTML, JS, CSS, fontes, ícones): o app abre rápido e abre mesmo sem rede.
- **Nenhuma resposta de `/app/v1` é guardada**: saldo, extrato e comprovantes ficam só na tela.
  Sem rede, o app mostra um aviso e nada é enviado.
- Uma versão nova do app é avisada ("Nova versão disponível") e aplicada quando o cliente toca em
  "Atualizar", nunca no meio de um pagamento.

### 5. Sessão como no desktop
- O token do cliente fica **só em memória** (num signal), nunca em `localStorage`, cookie ou
  IndexedDB. Fechar o app, ou recarregar a página, pede o código de novo. É o comportamento comum
  de app de banco e evita que o token sobreviva no aparelho.
- **Bloqueio por inatividade:** 5 minutos sem toque nem tecla voltam ao login e apagam o token.
  O app também sai quando volta do segundo plano depois desse tempo.
- `SESSION_EXPIRED` do `app-api` leva ao login com a mensagem dele.

### 6. Dinheiro e pagamentos como no desktop
- Valores em **centavos** (inteiros) no app; formatação pt-BR (R$ 1.234,56); nunca ponto flutuante
  para converter o que o cliente digita.
- Três passos em todo pagamento: **formulário → confirmação → comprovante**. A `Idempotency-Key`
  nasce na confirmação e se repete numa nova tentativa: dois toques ou um timeout não pagam duas
  vezes.
- O comprovante do Pix acompanha o status por alguns segundos (enviado → concluído ou devolvido).
- As mensagens de erro são as do `app-api`, mostradas como vêm.

### 7. Interface pensada para o celular
- **Mobile first:** uma coluna, navegação inferior (Início, Extrato, Pagar, Agenda) e alvos de
  toque de pelo menos 44 px; no computador, o app fica centralizado numa coluna.
- Teclado certo para cada campo (`inputmode="numeric"`, `decimal`, `email`) e `autocomplete`
  (`one-time-code` no código do e-mail).
- Tema **claro e escuro** seguindo o sistema; respeita `prefers-reduced-motion`.
- Acessibilidade: contraste AA, foco visível, rótulos em todos os campos, mensagens de erro ligadas
  ao campo (`aria-describedby`) e anunciadas (`aria-live`).

### 8. Identidade visual do M-Wall
- **Nome:** M-Wall. **Marca:** um "M" branco de traço arredondado sobre um quadrado de cantos
  arredondados em degradê laranja; o "M" desenha também a aba de uma carteira.
- **Cores:** laranja principal `#F26A1B` (marca, ícones, foco), `#C9490A` nos botões (texto branco
  com 4,7:1; o laranja principal com branco daria só 3,1:1), `#C2470A` para texto laranja, claro
  `#FFF1E6`; neutros quentes (`#1E1612` para texto, `#FAF7F4` de fundo). No tema escuro, fundo
  `#15100D` e botões `#FF8A3D` com texto escuro (7,6:1).
- **Tipografia:** Plus Jakarta Sans (variável), servida pelo próprio app (sem chamada a terceiros,
  funciona offline e cabe na CSP).
- Ícones do app (192, 512, *maskable* e o da tela inicial do iPhone) gerados a partir do SVG da marca.

## Ajustes feitos na implementação

- **Campos omitidos pelo kotlinx.serialization:** o `app-api` não envia um campo que tem o valor
  padrão (`Schedule.attempts = emptyList()`, `CodeSent.resendAfterSeconds = 60`). No contrato em
  TypeScript esses campos são opcionais, e o código aplica o mesmo padrão. Achado no teste ponta a
  ponta: sem isso, o detalhe do agendamento não mostrava o botão de cancelar.
- **Chave de idempotência sem `crypto.randomUUID`:** essa função só existe em contexto seguro. Um
  celular abrindo o app pelo IP da máquina, por http, não é um; a chave é montada com
  `crypto.getRandomValues`.

### 9. Estado e telas
- Cada fluxo tem uma classe de estado com **signals** (equivalente aos ViewModels do desktop) que
  não conhece o DOM: testada com Vitest e uma API falsa. Os componentes só desenham o estado.
- Rotas do Angular, com guarda de sessão: sem token, tudo volta para o login.

### 10. Testes
- **Unitários (Vitest):** formatação e leitura de valores e datas, sessão e inatividade, e os
  fluxos (login, cadastro, transferência, Pix, agendamentos) com a API falsa: a chave de
  idempotência se repete na nova tentativa, erros viram mensagens, nada sai com dados inválidos.
- **Ponta a ponta manual:** o container contra o ambiente local (core, pix, scheduler, otp,
  `app-api`), no navegador e com a instalação do PWA.

## Ordem de entrega
1. **Esqueleto e identidade:** projeto Angular, design system (tokens, logo, ícones), PWA
   instalável, nginx com o proxy e os cabeçalhos, Docker e compose dos dois tenants.
2. **Entrada e conta:** cadastro e login por código, início (saldo e últimos lançamentos) e extrato
   com detalhe.
3. **Transferência e Pix,** com confirmação e comprovante.
4. **Agendamentos:** lista, detalhe, cancelamento e novo agendamento.
5. **Acabamento:** inatividade, aviso sem rede, aviso de versão nova, CI, README e documentação da
   plataforma.

## Consequências
- **Positivas:** o mesmo produto chega ao celular sem loja e sem backend novo; o `app-api` prova
  que serve mais de um cliente; uma stack de front só (Angular) no monorepo.
- **Negativas:** o contrato é escrito de novo em TypeScript (o desktop usa as classes Kotlin do
  `app-contract`): uma mudança no contrato precisa ser repetida aqui; recarregar a página pede o
  código de novo.
- **Fora do escopo:** notificações push, biometria (WebAuthn), "lembrar de mim", modo offline com
  dados, app nas lojas.
