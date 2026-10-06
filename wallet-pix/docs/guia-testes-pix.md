# Guia de testes do Pix no ambiente local

Roteiro para testar de ponta a ponta o recebimento e o envio de Pix, com Postman ou com curl.
Todos os passos foram executados contra o ambiente local: a coleção passa com 53 requisições e
98 verificações, sem falhas.

## 1. Subir o ambiente

```bash
cd wallet-core && docker compose up -d     # wallet-core :8080, Postgres, Grafana :3000, Jaeger :16686, Prometheus :9090
cd ../wallet-pix && docker compose up -d   # pix-service :8081, spi-simulator :8090, LocalStack :4566
```

O wallet-core sobe com o perfil `dev`, que cria dois tenants. Cada tenant é um participante do Pix:

| Tenant | Client id / segredo | ISPB |
|---|---|---|
| demo-tenant | `demo-tenant` / `demo-secret-change-me-please` | 12345678 |
| segundo-tenant | `segundo-tenant` / `segundo-tenant-secret-please` | 87654321 |

O simulador do SPI ainda faz o papel de **um banco externo, ISPB 99999999**. Ele aceita todo Pix
enviado a ele, **exceto valores terminados em ,99**, que rejeita com AC03 para você testar o estorno.

Para parar sem perder dados: `docker compose stop` ou `docker compose down`, nunca com `-v`.

## 2. Postman

1. **Import** → selecione os dois arquivos de `wallet-pix/postman/`:
   - `wallet-pix.postman_collection.json` (a coleção);
   - `wallet-pix-local.postman_environment.json` (URLs e credenciais).
2. No canto superior direito, escolha o ambiente **Wallet Pix — local**.
3. Rode a coleção inteira (**Run collection**, na ordem) ou pasta por pasta.

Os scripts da coleção fazem o trabalho repetitivo:
- guardam os tokens, os ids de conta e os EndToEndIds nas variáveis da coleção;
- geram um CPF válido novo a cada execução, para nunca dar "cliente já existe";
- esperam 2,5 s depois de cada passo assíncrono (`asyncWaitMs` no ambiente). Aumente se a sua
  máquina estiver lenta.

As pastas dependem umas das outras, então rode-as em ordem. Os tokens valem 10 minutos: se
aparecer 401 no meio do caminho, rode a pasta **01 · Autenticação** de novo.

### O que cada pasta testa

| Pasta | Cenário | Resultado esperado |
|---|---|---|
| 00 Saúde | Os três serviços respondem | `UP` |
| 01 Autenticação | Tokens completos, `pix:send` e um só com `accounts:read` | 200 |
| 02 Preparação | Maria (demo-tenant) com 500,00 e João (segundo-tenant) | Saldo da Maria 500,00 |
| 03 Titularidade | `holder-check` correto, CPF errado, conta inexistente, conta de outro tenant | `VALID`, `TAX_ID_MISMATCH`, `ACCOUNT_NOT_FOUND` (×2) |
| 04 Recebimento | Banco externo manda 25,00 para a Maria | Saldo 525,00, e o extrato mostra o EndToEndId |
| | CPF não é do titular | RJCT **BE01**, sem crédito |
| | Conta inexistente | RJCT **AC03** |
| | Conta tipo CACC | RJCT **AC14** |
| 05 Envio | Maria → João, 30,00 | 202 `SENT` → `COMPLETED`; João 30,00; Maria 495,00 |
| | Mesma `Idempotency-Key` | 200 com o mesmo EndToEndId, sem débito novo |
| | Mesma chave com outro valor | 409 `IDEMPOTENCY_KEY_REUSED` |
| | Maria → externo, 20,00 | `COMPLETED` |
| | Maria → externo, 10,99 | `REFUNDED` com motivo AC03; o débito volta, saldo 475,00 |
| | Externo devolve 5,00 dos 20,00 (pacs.004) | `RETURNED`; saldo 480,00 |
| 06 Recusas | Saldo insuficiente | 422 `INSUFFICIENT_FUNDS` |
| | Acima de 5000,00 | 422 `POLICY_MAX_AMOUNT` |
| | CPF do pagador errado | 422 `PAYER_TAX_ID_MISMATCH` |
| | Token sem `pix:send` / sem token | 403 / 401 |
| | Sem `Idempotency-Key` / ISPB do recebedor inválido | 400 `IDEMPOTENCY_KEY_REQUIRED` / 400 `VALIDATION_FAILED` |
| | Outro tenant consulta o Pix da Maria | 404 |
| | Fim da pasta | Saldo continua 480,00 |
| 07 Mensagens e eventos | Mensagens do SPI simulado, PixEvents e DLQ | Para inspeção |
| 08 Limpeza | Esvazia a DLQ e a fila de eventos | Opcional |

## 3. Os mesmos passos com curl

Use no Git Bash. Os blocos usam o Python só para ler campos do JSON; se você tiver `jq`, troque
`json '...'` por `jq -r '...'`.

> **Sem acentos nos comandos curl.** No Windows, o Git Bash não envia caracteres como "ã" em
> UTF-8, e a API recusa o JSON (`400 MALFORMED_REQUEST`). Por isso os nomes abaixo vão sem
> acento. No Postman, acentos funcionam normalmente.

```bash
WC=http://localhost:8080; PIX=http://localhost:8081; SIM=http://localhost:8090; LS=http://localhost:4566
json() { python -c "import sys,json; d=json.load(sys.stdin); print(eval('d' + sys.argv[1]))" "$1"; }
cpf()  { python -c "
import random
d=[random.randint(0,9) for _ in range(9)]
for n in (10,11):
    r=(sum(v*w for v,w in zip(d,range(n,1,-1)))*10)%11; d.append(0 if r==10 else r)
print(''.join(map(str,d)))"; }
key()  { python -c "import uuid; print(uuid.uuid4())"; }
```

### 3.1 Tokens

```bash
T1=$(curl -s -X POST -u demo-tenant:demo-secret-change-me-please $WC/v1/auth/token | json "['access_token']")
T1SEND=$(curl -s -X POST -u demo-tenant:demo-secret-change-me-please "$WC/v1/auth/token?scope=pix:send" | json "['access_token']")
T2=$(curl -s -X POST -u segundo-tenant:segundo-tenant-secret-please $WC/v1/auth/token | json "['access_token']")
T2SEND=$(curl -s -X POST -u segundo-tenant:segundo-tenant-secret-please "$WC/v1/auth/token?scope=pix:send" | json "['access_token']")
```

### 3.2 Preparação: Maria (pagadora) e João (recebedor)

```bash
MARIA_CPF=$(cpf)
R=$(curl -s -X POST $WC/v1/customers -H "Authorization: Bearer $T1" -H 'Content-Type: application/json' \
  -d "{\"name\":\"Maria Pagadora\",\"taxId\":\"$MARIA_CPF\",\"externalRef\":\"curl-$(key)\"}")
MARIA_ID=$(echo "$R" | json "['account']['id']")
MARIA_BRANCH=$(echo "$R" | json "['account']['branch']")
MARIA_NUM=$(echo "$R" | json "['account']['number']"); MARIA_DIG=$(echo "$R" | json "['account']['checkDigit']")
MARIA_ACC=$MARIA_NUM$MARIA_DIG      # no SPI, a conta vai com o dígito no final

curl -s -X POST $WC/v1/accounts/$MARIA_ID/deposits -H "Authorization: Bearer $T1" -H "Idempotency-Key: $(key)" \
  -H 'Content-Type: application/json' -d '{"amount":500.00,"description":"Saldo inicial"}'

JOAO_CPF=$(cpf)
R=$(curl -s -X POST $WC/v1/customers -H "Authorization: Bearer $T2" -H 'Content-Type: application/json' \
  -d "{\"name\":\"Joao Recebedor\",\"taxId\":\"$JOAO_CPF\",\"externalRef\":\"curl-$(key)\"}")
JOAO_ID=$(echo "$R" | json "['account']['id']"); JOAO_BRANCH=$(echo "$R" | json "['account']['branch']")
JOAO_ACC=$(echo "$R" | json "['account']['number']")$(echo "$R" | json "['account']['checkDigit']")

saldo() { curl -s $WC/v1/accounts/$1/balance -H "Authorization: Bearer $2"; echo; }
saldo $MARIA_ID $T1    # 500.00
```

### 3.3 Conferência de titularidade

```bash
curl -s -X POST $WC/v1/accounts/holder-check -H "Authorization: Bearer $T1" -H 'Content-Type: application/json' \
  -d "{\"branch\":\"$MARIA_BRANCH\",\"number\":\"$MARIA_NUM\",\"checkDigit\":\"$MARIA_DIG\",\"taxId\":\"$MARIA_CPF\"}"
# {"result":"VALID","accountId":"..."}   — troque o taxId por 11144477735 para ver TAX_ID_MISMATCH
```

### 3.4 Recebimento

```bash
recebe() {  # recebe VALOR CPF CONTA TIPO
  curl -s -X POST $SIM/simulate/incoming -H 'Content-Type: application/json' -d "{\"payeeIspb\":\"12345678\",
    \"branch\":\"$MARIA_BRANCH\",\"accountNumber\":\"$3\",\"accountType\":\"$4\",\"taxId\":\"$2\",
    \"name\":\"Maria\",\"amount\":$1,\"payerName\":\"Carlos do Banco Externo\"}" | json "['endToEndId']"; }

E2E_IN=$(recebe 25.00 $MARIA_CPF $MARIA_ACC TRAN); sleep 3; saldo $MARIA_ID $T1      # 525.00
E2E_BE01=$(recebe 10.00 11144477735 $MARIA_ACC TRAN)   # CPF não é do titular
E2E_AC03=$(recebe 10.00 $MARIA_CPF 999999990 TRAN)     # conta inexistente
E2E_AC14=$(recebe 10.00 $MARIA_CPF $MARIA_ACC CACC)    # tipo de conta errado
sleep 3; curl -s $SIM/simulate/messages | python -m json.tool | grep -B3 -A3 '"RJCT"' | head -40
saldo $MARIA_ID $T1    # continua 525.00
```

### 3.5 Envio

```bash
envia() {  # envia CHAVE VALOR ISPB AGENCIA CONTA CPF_RECEBEDOR [CPF_PAGADOR] [TOKEN]
  curl -s -w '\nHTTP %{http_code}\n' -X POST $PIX/v1/pix/payments -H "Authorization: Bearer ${8:-$T1SEND}" \
    -H "Idempotency-Key: $1" -H 'Content-Type: application/json' -d "{\"payerAccountId\":\"$MARIA_ID\",
    \"payerTaxId\":\"${7:-$MARIA_CPF}\",\"payee\":{\"ispb\":\"$3\",\"branch\":\"$4\",\"accountNumber\":\"$5\",
    \"taxId\":\"$6\",\"name\":\"Recebedor\"},\"amount\":$2,\"description\":\"teste curl\"}"; }
status() { curl -s $PIX/v1/pix/payments/$1 -H "Authorization: Bearer $T1SEND"; echo; }

K1=$(key); envia $K1 30.00 87654321 $JOAO_BRANCH $JOAO_ACC $JOAO_CPF          # HTTP 202, status SENT
# copie o endToEndId da resposta:
E2E_OUT1=<endToEndId>; sleep 3; status $E2E_OUT1                               # COMPLETED
saldo $JOAO_ID $T2; saldo $MARIA_ID $T1                                         # 30.00 e 495.00
envia $K1 30.00 87654321 $JOAO_BRANCH $JOAO_ACC $JOAO_CPF                       # HTTP 200 (replay)
envia $K1 31.00 87654321 $JOAO_BRANCH $JOAO_ACC $JOAO_CPF                       # HTTP 409 IDEMPOTENCY_KEY_REUSED

envia $(key) 20.00 99999999 0042 1234565 11144477735                            # externo, aceito
E2E_OUT2=<endToEndId>; sleep 3; status $E2E_OUT2                               # COMPLETED
envia $(key) 10.99 99999999 0042 1234565 11144477735                            # externo rejeita ,99
E2E_OUT3=<endToEndId>; sleep 3; status $E2E_OUT3                               # REFUNDED, reasonCode AC03
saldo $MARIA_ID $T1                                                             # 475.00

curl -s -X POST $SIM/simulate/return -H 'Content-Type: application/json' \
  -d "{\"endToEndId\":\"$E2E_OUT2\",\"amount\":5.00,\"reason\":\"MD06\"}"       # devolução (pacs.004)
sleep 3; status $E2E_OUT2; saldo $MARIA_ID $T1                                 # RETURNED e 480.00
```

### 3.6 Recusas (nenhuma debita)

```bash
envia $(key) 4999.00 99999999 0042 1234565 11144477735                 # 422 INSUFFICIENT_FUNDS
envia $(key) 6000.00 99999999 0042 1234565 11144477735                 # 422 POLICY_MAX_AMOUNT
envia $(key) 1.00 99999999 0042 1234565 11144477735 11144477735        # 422 PAYER_TAX_ID_MISMATCH
T1READ=$(curl -s -X POST -u demo-tenant:demo-secret-change-me-please "$WC/v1/auth/token?scope=accounts:read" | json "['access_token']")
envia $(key) 1.00 99999999 0042 1234565 11144477735 "" $T1READ         # 403
curl -s -o /dev/null -w '%{http_code}\n' $PIX/v1/pix/payments/$E2E_OUT1 -H "Authorization: Bearer $T2SEND"   # 404
saldo $MARIA_ID $T1                                                    # 480.00
```

### 3.7 Inspeção

```bash
curl -s $SIM/simulate/messages | python -m json.tool | head -60       # tráfego do SPI simulado
python scripts/pixdev.py events                                         # PixEvents publicados (consome a fila)
curl -s -X POST $LS -d "Action=GetQueueAttributes&QueueUrl=$LS/000000000000/wallet-pix-spi-inbound-dlq&AttributeName.1=ApproximateNumberOfMessages"
```

## 4. Onde acompanhar

| O quê | Onde |
|---|---|
| Dashboard do Pix | Grafana `http://localhost:3000` (admin/admin) → pasta Wallet Core → **Wallet Core — Pix** |
| Um Pix de ponta a ponta | Jaeger `http://localhost:16686`: serviço `pix-service` ou `spi-simulator`. Um trace cruza simulador → filas → pix-service → wallet-core |
| Logs | Grafana → Explore → Loki: `{service_name="pix-service"}` |
| Métricas cruas | `http://localhost:8081/actuator/prometheus` (`pix_payments_total`, `pix_payment_duration_seconds`, `pix_queue_messages`...) |

## 5. Problemas comuns

| Sintoma | Causa provável |
|---|---|
| 401 no meio da coleção | O token expirou (10 min): rode a pasta 01 de novo |
| 409 `CUSTOMER_ALREADY_EXISTS` no curl | O mesmo CPF foi usado duas vezes no tenant: gere outro com `cpf` |
| Status continua `SENT` | O processamento ainda não terminou: espere alguns segundos ou aumente `asyncWaitMs` |
| `pacs.002 for unknown Pix` nos logs do simulador | O simulador foi reiniciado e esqueceu as rotas em andamento: comece um Pix novo |
| "Mensagens na DLQ" vermelho no dashboard | Há mensagem com falha definitiva: veja os logs (`sending towards the DLQ`) e esvazie com a pasta 08 |
| `pix-service` não sobe | O wallet-core precisa estar no ar antes, porque o pix-service usa a rede e o Postgres dele |
