# ADR-003 (wallet-app) — Clientes abertos pelo console entram no app pelo e-mail do cadastro

**Status:** proposta. Complementa a ADR-002.

## Contexto
Com a ADR-002, o app só aceitava clientes que se cadastravam por ele. Um cliente aberto pelo operador
no console (por exemplo, o CPF 800.440.510-00 do teste) recebia "procure o atendimento" e não tinha
como entrar.

A ideia de associar a conta depois do código do **cadastro** não é segura: esse código prova a posse
do e-mail que **quem está pedindo** digitou, não que essa pessoa é a dona da conta. Quem soubesse um
CPF (que não é segredo) digitaria o próprio e-mail e ficaria com a conta.

## Decisões

### 1. O e-mail confiável é o do cadastro feito pelo operador
O cliente do wallet-core ganha um e-mail **opcional**, informado na abertura de conta e alterável
depois (`PUT /v1/customers/{id}/email`, escopo `customers:write`). Quem informa é o operador, que
conhece o cliente. O e-mail não vai para os eventos do outbox, como o CPF.

O cadastro pelo app também grava no core o e-mail que o cliente confirmou com o código.

### 2. A associação acontece no login, nunca no cadastro
No `login/start`, se o CPF não tem login no app mas o core tem esse cliente **com e-mail e conta de
pagamento** (`GET /v1/customers/contact?taxId=`, escopo `accounts:read`), o código vai para o e-mail do
core. No `login/confirm`, conferido o código, o app-api cria o login já ativo, ligado à conta que o
core tem e com aquele e-mail. Daí em diante é um login comum.

Para o cliente: ele só precisa usar "Entrar" com o CPF. Não se cadastra nem digita e-mail.

### 3. O cadastro nunca associa
Um cadastro pelo app com o CPF de um cliente do console continua recusado depois do código:
- com e-mail no core: `CUSTOMER_EXISTS_USE_LOGIN`, "Este CPF já tem conta. Use Entrar: o código vai
  para o e-mail cadastrado no atendimento.";
- sem e-mail no core: `CUSTOMER_EXISTS_OUTSIDE_APP`, "... procure o atendimento e cadastre seu e-mail."

### 4. Sem pistas
O `login/start` responde igual para CPF com login, cliente do console com e-mail, cliente do console sem
e-mail e CPF inexistente (ADR-002, decisão 3). Só quem tem acesso ao e-mail certo recebe código.

### 5. Console
- Abrir conta: campo **E-mail (opcional)**, com o aviso de que sem e-mail o cliente não entra no app.
- Tela da conta: mostra o e-mail do titular, com "cadastrar" ou "alterar".

## Consequências
- **Positivas:** clientes do console usam o app sem passo extra; nenhum e-mail digitado por terceiros
  abre uma conta; o operador vê e corrige o e-mail do cliente.
- **Negativas:** o banco de dados da aplicação (`wallet_app`) passa a poder atualizar uma coluna de
  `customer` (só `email`); trocar o e-mail no core **não** muda o e-mail de um login que já existe no app
  (o login guarda o e-mail com que foi criado).
- **Fora do escopo:** o cliente trocar o próprio e-mail pelo app; avisar o e-mail antigo quando o
  operador trocar.
