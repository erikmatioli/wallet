# ADR-002 (wallet-app) — Cadastro e login por código enviado ao e-mail

**Status:** proposta. Substitui as partes de senha da ADR-001 (decisão 4).

## Contexto
O app usa CPF e senha (ADR-001). Para clientes abertos pelo console, o primeiro acesso usava o próprio
CPF como senha provisória, o que qualquer um que soubesse o CPF podia usar. Decidimos trocar a senha
por um código de uso único enviado por e-mail, gerado pelo `wallet-otp` (ADR-001 do wallet-otp).

A base de dados será apagada depois desta mudança: não há clientes atuais para migrar.

## Decisões

### 1. Sem senha
O login deixa de ter senha. Saem o hash BCrypt, o bloqueio por 5 senhas erradas, a política de senha
e o primeiro acesso com o CPF. Quem limita tentativas agora é o motor de códigos.

### 2. Cadastro: o código prova a posse do e-mail
1. **Início** (`POST /app/v1/signup/start`): CPF/CNPJ, nome e e-mail. O app-api valida os formatos e
   pede ao wallet-otp um código de finalidade `SIGNUP`, com o e-mail como contexto. Responde o
   `challengeId` e o e-mail mascarado.
2. **Confirmação** (`POST /app/v1/signup/confirm`): `challengeId`, código e os mesmos dados. O app-api
   confere o código no wallet-otp; só então abre o cliente e a conta no wallet-core, grava o login
   com o e-mail e devolve a sessão.

O e-mail é gravado já verificado. O mesmo e-mail pode estar em vários logins (CPF e CNPJ de uma
mesma pessoa).

A recuperação de um cadastro interrompido (login `PENDING`) continua como na ADR-001.

Um CPF que já tem login, ou que já existe no wallet-core sem login (aberto pelo console), só é recusado
**depois** do código conferido. Assim, só quem prova a posse de um e-mail descobre que um CPF tem
conta.

### 3. Login: o código é a senha
1. **Início** (`POST /app/v1/login/start`): só o CPF/CNPJ. Se houver login ativo, o app-api pede ao
   wallet-otp um código de finalidade `LOGIN` para o e-mail cadastrado. A resposta é **sempre a
   mesma**, exista o CPF ou não: um `challengeId` e a frase "Se houver conta para este CPF,
   enviamos um código para o e-mail cadastrado". Sem e-mail mascarado, que revelaria que a conta
   existe. Para um CPF sem conta, o `challengeId` é inventado e nunca confere.
2. **Confirmação** (`POST /app/v1/login/confirm`): `challengeId`, CPF e código. Conferido, devolve a
   sessão, como hoje.

Erros do código chegam ao cliente em português: código errado (com as tentativas que restam), código
vencido, tentativas esgotadas, espere para pedir outro.

### 4. Desktop
- **Login:** tela do CPF, depois tela do código, com "Reenviar código" liberado depois de 60 s e
  "Trocar CPF".
- **Abrir conta:** nome, CPF/CNPJ e e-mail, depois a mesma tela do código.
- O resto (sessão em memória, 30 minutos, bloqueio após 5 minutos sem uso) não muda.

### 5. Dados
Migration nova no banco `app`: `customer_login` ganha `email` (obrigatório) e perde `password_hash`,
`failed_attempts` e `locked_until`. O app-api chama o wallet-otp com as credenciais do tenant, como já
faz com o core, o wallet-pix e o scheduler (`APP_OTP_URL`, padrão `http://localhost:8085`).

## Ajustes feitos na implementação

- **Mesmo limite para todo CPF:** o wallet-otp só vê os CPFs que têm conta, então o "aguarde 60 s" dele
  diferenciaria um CPF com conta de um sem. O app-api aplica as mesmas regras (1 código por minuto e 5 por
  hora) a qualquer CPF antes de chamar o wallet-otp (`StartThrottle`). Fica em memória, por instância: um
  reinício esquece, e os limites do wallet-otp continuam valendo atrás dele.
- **Mensagens do código:** código errado e código inexistente leem igual ("Código incorreto. Confira o
  e-mail e tente de novo."), sem o número de tentativas restantes. Vencido, já usado ou substituído leem
  "Este código não vale mais. Peça um novo."; bloqueado, "Muitas tentativas erradas. Peça um novo código."
- **Risco residual de enumeração:** quem errar 5 vezes o código de um CPF com conta recebe "bloqueado", e
  de um CPF sem conta continua recebendo "incorreto". Descobrir assim exige gastar os pedidos que o limite
  permite. Aceito para o MVP.
- **Logins antigos:** a migration V3 deixa `email` nulo nos logins feitos com senha; eles não conseguem
  mais entrar. Como a base foi apagada depois desta mudança, nenhum existe.
- **Respostas:** os dois "start" respondem `200` com `challengeId`, a mensagem e `resendAfterSeconds`; o
  `429` traz `retryAfterSeconds` no corpo e no cabeçalho `Retry-After`.

## Ordem de entrega (wallet-otp e wallet-app juntos)
1. **wallet-otp esqueleto:** projeto, banco `otp`, Mailpit no compose, health; escopo `otp:use` no core.
2. **Motor de códigos:** desafios, regras, limites, envio por SMTP, API, testes.
3. **app-api:** cadastro e login por código; sai a senha; migration.
4. **Desktop:** telas novas de login e cadastro.
5. **Ambiente do zero:** apagar os bancos, subir tudo, validar de ponta a ponta (cadastro, login,
   código errado, vencido, limites, e-mail de várias contas) e atualizar READMEs, guia e CI.

## Consequências
- **Positivas:** nenhuma senha para guardar ou vazar; o cadastro prova a posse do e-mail; a fraqueza
  do CPF como senha desaparece.
- **Negativas:** entrar exige abrir o e-mail toda vez. A conta fica tão segura quanto o e-mail do
  cliente. Se o envio de e-mail cair, ninguém entra.
- **Fora do escopo:** clientes abertos pelo console (resolvido depois, na ADR-003: entram pelo login
  com o e-mail que o operador cadastrou); trocar o e-mail de um login; aprovar pagamentos com código (o motor já
  comporta, a tela não).
