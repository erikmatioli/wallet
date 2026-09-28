# ADR-001 — Arquitetura do Wallet Console

**Status:** aceita

## Contexto
Precisamos de uma interface para operar as funcionalidades da primeira versão do wallet-core, pensada
como **microfrontend** — uma peça de um produto que poderá ter outras interfaces no futuro. Hoje não
existe nenhuma outra aplicação frontend nem um "shell" que componha várias delas.

## Decisões

### 1. Angular com componentes standalone, zoneless e signals
É o padrão de projetos novos do Angular 21: sem `NgModule`, sem Zone.js, estado local em `signal()`.
Menos código de infraestrutura e bundle inicial pequeno (~72 kB transferidos), com cada rota carregada
sob demanda (lazy loading).

### 2. Mesma origem: chamadas relativas + proxy reverso (nada de CORS)
O código só chama caminhos relativos (`/v1/customers`, ...). Em desenvolvimento, `proxy.conf.json`
encaminha `/v1/**` ao wallet-core; em produção, o nginx da imagem faz o mesmo (`nginx.conf`).
- (+) O wallet-core **não precisa de nenhuma configuração de CORS**, e portanto nenhuma mudança de
  segurança no backend para servir este frontend.
- (+) Nenhuma URL de backend embutida no bundle: a mesma imagem serve em qualquer ambiente.
- (−) O console depende de estar atrás de um proxy que roteie `/v1/**`; abrir os arquivos estáticos
  sozinhos, sem o nginx, não funciona.

### 3. Sessão em `sessionStorage`, JWT anexado por interceptor
O usuário informa client id/secret do tenant; o console troca por um JWT (`POST /v1/auth/token`, Basic)
e o guarda em `sessionStorage`: sobrevive a um refresh, some ao fechar a aba, não é compartilhado entre abas.
O client secret **nunca é armazenado** — só usado na chamada de login.
- **Limitação assumida:** qualquer armazenamento acessível por JavaScript é exposto se houver XSS. Para um
  console interno de operação isso é aceitável; se um dia houver sessão de cliente final, o caminho é um BFF
  com cookie `HttpOnly`, não endurecer este mecanismo.

### 4. Uma `Idempotency-Key` nova por clique
O backend exige `Idempotency-Key` em depósito/saque/transferência. Cada ação do operador gera uma
chave nova (`crypto.randomUUID()`), o que é correto para "um clique = uma operação". Ainda **não** há
"repetir exatamente a mesma requisição" após erro de rede: reenviar hoje cria uma operação nova.
Se isso virar necessidade, a chave precisa ser gerada ao abrir o formulário e reaproveitada nas tentativas.

### 5. Sem biblioteca de UI
SCSS com design tokens (variáveis CSS) e um parcial compartilhado. Para três telas de um console interno,
Angular Material/Bootstrap trariam bundle, tema e curva de aprendizado desproporcionais.

### 6. Microfrontend: preparado, mas não federado ainda
Hoje o console é uma aplicação independente (build, imagem e release próprios, tag `wallet-console-vX.Y.Z`).
**Não** foi configurado Module/Native Federation porque não existe um shell (host) para consumir o console
como remote — configurar um lado só seria infraestrutura sem uso e sem como validar. O que já o deixa
pronto para isso: rotas com lazy loading, ausência de estado global compartilhado, acoplamento ao backend
apenas por caminhos relativos e um único `models.ts` como contrato. Quando existir um segundo frontend/shell,
o passo seguinte é expor as rotas como remote via Native Federation e decidir como sessão/autenticação
são compartilhadas entre as peças (hoje a sessão pertence só a este app).

## Consequências
- (+) Console pequeno, sem dependências pesadas, sem mudança no backend.
- (−) Sem listagem/busca de clientes e contas: o wallet-core ainda não expõe esses endpoints.
- (−) Login manual por tenant (client credentials); não há usuários individuais/perfis de operador.
