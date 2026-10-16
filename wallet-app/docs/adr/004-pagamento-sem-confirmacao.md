# ADR-004 (wallet-app) — Pagamento sem confirmação: o app confirma de novo, com a mesma chave

**Status:** aceita e implementada. Complementa a ADR-001, decisão 6 (os três passos do pagamento).

## Contexto
Em 15/10/2026, no ambiente local com todos os serviços no ar, um Pix pelo M-Wall respondeu **erro 500**, e o
pagamento **tinha sido feito**: o app-api desistiu de esperar o wallet-pix depois de 10 segundos, e o wallet-pix
concluiu o Pix 25 segundos depois (o trace no Jaeger mostra o pix-service parado entre as etapas, com a máquina sem
memória). Um cliente que visse o erro e pagasse de novo, por outra confirmação, pagaria duas vezes.

Duas falhas:

- **O timeout virava 500.** O app-api já tratava "dependência fora do ar" como 503 ("tente de novo"), mas só quando
  o `RestClient` lança `ResourceAccessException`. Um timeout **durante a leitura da resposta** chega como um
  `RestClientException` comum, com o `SocketTimeoutException` por baixo, e escapava como 500. O mesmo derrubou um
  login (a chamada ao wallet-otp) no mesmo dia.
- **"Tente de novo" não diz o que importa.** Num pagamento, a falta de resposta não quer dizer que nada saiu.

A garantia de não pagar duas vezes já existe: a `Idempotency-Key` nasce na confirmação e fica enquanto o cliente
está nela; o wallet-pix e o wallet-core devolvem o pagamento original para a mesma chave, inclusive com o primeiro
pedido ainda em andamento (o débito no core usa `pix-debit-<chave>`).

## Decisões

### 1. Resposta perdida é 503, nunca 500
Nos quatro clientes do app-api (wallet-core, wallet-pix, wallet-scheduler, wallet-otp), além do
`ResourceAccessException`, um `RestClientException` com uma `IOException` na causa (timeout, conexão quebrada) vira
`DependencyUnavailableException`: 503 `TEMPORARILY_UNAVAILABLE`.

### 2. Num pagamento, `PAYMENT_NOT_CONFIRMED`
Quando a resposta perdida é a de um pagamento (`POST /app/v1/transfers` e `POST /app/v1/pix`), o 503 traz o código
`PAYMENT_NOT_CONFIRMED` e a mensagem "Ainda não temos a confirmação do pagamento. Confirme de novo: o mesmo pagamento
nunca sai duas vezes."

### 3. O app confirma de novo sozinho
O M-Wall e o desktop, ao receber `PAYMENT_NOT_CONFIRMED`, `TEMPORARILY_UNAVAILABLE` ou nenhuma resposta, repetem a
confirmação **com a mesma chave** até 3 vezes, a cada 3 segundos, mostrando "Confirmando o seu pagamento…". Assim que
o pagamento original volta, mostram o comprovante. Esgotadas as tentativas, ficam na confirmação com a mensagem do
app-api, e "Confirmar" continua sendo uma repetição. Uma recusa (saldo, bloqueio, dados) nunca é repetida.

A repetição fica no app, e não no app-api: o app espera no máximo 15 segundos por resposta, e o app-api já pode
gastar 10 esperando o wallet-pix.

## Consequências
- **Positivas:** o cliente não vê erro para um pagamento feito; um timeout passa a ser um "confirmando" curto; nenhum
  caminho paga duas vezes.
- **Negativas:** uma confirmação pode levar até cerca de 1 minuto no pior caso (4 tentativas de até 15 segundos); o
  código `PAYMENT_NOT_CONFIRMED` entra no contrato do app-api.
