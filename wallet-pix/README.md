# wallet-pix

Serviço de **recebimento e envio de Pix** da plataforma. Fica fora do `wallet-core` e conversa
com o SPI (Banco Central) por mensagens num barramento **SNS/SQS**. O dinheiro continua só no
ledger do `wallet-core`: o serviço Pix usa as APIs dele para validar contas, creditar, debitar e
estornar, sempre como o tenant dono da conta.

Decisões e alternativas descartadas: [`docs/adr/001-servico-pix-e-barramento.md`](docs/adr/001-servico-pix-e-barramento.md).

## Módulos

| Módulo | O que é |
|---|---|
| `pix-messages` | Contratos do barramento: mensagens do SPI (pacs.008/002/004 em JSON com as tags ISO 20022) e os eventos `PixEvent` |
| `pix-service` | O serviço. Arquitetura hexagonal por pacotes (`domain`, `application`, `adapter.*`), verificada por ArchUnit |
| `spi-simulator` | Simulador do SPI para desenvolvimento local. **Nunca vai para produção** |

## Como subir

O compose do wallet-pix roda **ao lado** do compose do wallet-core: ele entra na rede dele para
alcançar `wallet-core`, `postgres` e `otel-collector`.

```bash
(cd ../wallet-core && docker compose up -d)   # perfil dev: cria demo-tenant (12345678) e segundo-tenant (87654321)
docker compose up -d --build                  # LocalStack, banco "pix", pix-service (:8081), spi-simulator (:8090)
```

- O banco `pix` é criado no Postgres do wallet-core pelo serviço `pix-db-init`, com as roles
  `pix_owner` (migrations) e `pix_app` (runtime). O banco do wallet-core não é tocado.
- Parar sem perder dados: `docker compose stop` ou `docker compose down` (**sem `-v`**).

### LocalStack

O compose fixa `localstack/localstack:4.12`. A partir de 2026 a imagem `latest` só sobe com
`LOCALSTACK_AUTH_TOKEN`, e a 4.12 é a última versão community que sobe sem token. Ela não
recebe mais atualizações, o que é aceitável para um barramento só local. Para usar a versão
atual, crie uma conta gratuita, troque a imagem e passe `LOCALSTACK_AUTH_TOKEN` no ambiente.
Nada no código muda: o AWS SDK só vê o endpoint.

## Fluxos

### Recebimento (somos o PSP do recebedor)

```
SPI ──pacs.008──▶ pix-service ──holder-check──▶ wallet-core
SPI ◀─pacs.002 ACSP/RJCT── pix-service
SPI ──pacs.002 ACSC──▶ pix-service ──deposit (Idempotency-Key = EndToEndId)──▶ wallet-core
```

1. O ISPB do recebedor no pacs.008 identifica o tenant. A conta precisa ser `TRAN` e o
   `holder-check` do wallet-core precisa confirmar agência, conta e CPF/CNPJ.
2. A resposta é ACSP ou RJCT com o motivo do catálogo:

   | Código | Quando |
   |---|---|
   | AC03 | conta inexistente ou número inválido |
   | AC06 | conta (ou cliente) bloqueada |
   | AC07 | conta encerrada |
   | AC14 | tipo de conta diferente de TRAN |
   | BE01 | CPF/CNPJ não é do titular |

   Confira os códigos contra a versão vigente do Catálogo de Mensagens do SPI antes de produção.
3. Só depois do ACSC o cliente é creditado. Se o SPI mandar RJCT depois de um ACSP, nada é creditado.

### Envio (somos o PSP do pagador)

```
cliente ──POST /v1/pix/payments (JWT pix:send)──▶ pix-service
            regras ─▶ holder-check do pagador ─▶ withdrawal (débito) ─▶ 202 + EndToEndId
pix-service ──pacs.008──▶ SPI ──pacs.002 ACSC──▶ concluído
                              ──pacs.002 RJCT──▶ POST /v1/transactions/{débito}/reversals
                              ──pacs.004──────▶ deposit (devolução)
```

**API:** `POST /v1/pix/payments`, porta 8081.

- **Token:** do próprio wallet-core, com o escopo `pix:send`:
  `POST /v1/auth/token?scope=pix:send`. O tenant, e portanto o ISPB do pagador, sai do token,
  nunca do corpo.
- **Header obrigatório:** `Idempotency-Key`. Repetir a chave com o mesmo pagamento devolve o
  original (200); com outro pagamento, devolve 409.

```json
{
  "payerAccountId": "01a1...",
  "payerTaxId": "52998224725",
  "payee": { "ispb": "87654321", "branch": "0001", "accountNumber": "001000029", "taxId": "39053344705", "name": "Recebedor" },
  "amount": 10.00,
  "description": "aluguel"
}
```

`accountNumber` é o número da conta com o dígito no final, como o SPI transporta.

**Respostas:**

| Status | Significado |
|---|---|
| 202 | Pagador debitado e pacs.008 a caminho. Corpo: `endToEndId`, `status`, `debitTransactionId` |
| 200 | Replay da mesma `Idempotency-Key` |
| 422 | Recusado, nada debitado: `POLICY_MAX_AMOUNT`, `INSUFFICIENT_FUNDS`, `PAYER_TAX_ID_MISMATCH`, `PAYER_ACCOUNT_NOT_FOUND`… |
| 409 | `IDEMPOTENCY_KEY_REUSED` |
| 503 | Dependência fora; repita com a mesma chave (um débito já feito é reaproveitado, não repetido) |

`GET /v1/pix/payments/{endToEndId}` devolve o status de um Pix enviado pelo próprio tenant.

**Ordem do envio:**
1. **Regras** (`PaymentPolicy`). Hoje só o limite por transação (`pix.policies.max-amount`,
   padrão 5000,00). Janela de horário e limites diários entram na mesma lista.
2. **Pagador.** O CPF/CNPJ informado precisa ser do titular. O nome vem do wallet-core.
3. **Débito** atômico no wallet-core, com a chave `pix-debit-<Idempotency-Key>`. Não há
   consulta prévia de saldo.
4. **Registro.** O pagamento é gravado como SENT e o pacs.008 vai para o outbox na mesma
   transação.

## Barramento

| Tópico SNS | Fila SQS | Sentido |
|---|---|---|
| `spi-to-psp` | `wallet-pix-spi-inbound` (+ DLQ, 5 tentativas) | SPI → pix-service |
| `psp-to-spi` | `spi-simulator-inbound` | pix-service → SPI |
| `pix-payment-events` | `pix-events-dev` (inspeção local) | pix-service → interessados |

- Tópicos, filas e assinaturas são criados por `docker/localstack/init-bus.sh`, com raw
  message delivery.
- As mensagens saem por um **outbox transacional**: só existe mensagem se a mudança de estado
  que a gerou foi commitada.
- Toda mensagem recebida é deduplicada pelo id (`BizMsgIdr`), e todo crédito e débito é
  idempotente no wallet-core.

### Contrato das mensagens do SPI

JSON com as tags ISO 20022 (`AppHdr`/`Document`, `GrpHdr`, `CdtTrfTxInf`, `EndToEndId`,
`IntrBkSttlmAmt`, `Dbtr`, `CdtrAcct`, `OrgnlMsgId`, `TxSts`…). Veja `SpiMessages.java`.

Simplificações em relação ao catálogo real:
- uma transação por mensagem;
- `Id` do participante achatado (CPF/CNPJ só com dígitos);
- agentes identificados direto pelo ISPB.

Identificadores no formato do SPI:
- EndToEndId: `E` + ISPB + `yyyyMMddHHmm` (UTC) + 11 alfanuméricos;
- id de mensagem: `M` + ISPB + 23 alfanuméricos.

## Simulador do SPI

- **Pix entre os dois tenants do seed:** passa pelo pix-service **dos dois lados**, como
  pagador e como recebedor.
- **ISPB externo fictício `99999999`:** o simulador responde ACSC, ou RJCT AC03 quando o valor
  termina em `,99`, para testar o estorno.
- **Endpoints:**
  - `POST /simulate/incoming`: um Pix chegando de fora;
  - `POST /simulate/return`: uma devolução (pacs.004);
  - `GET /simulate/messages`: o que passou pelo "SPI".

O estado fica em memória: reiniciar o simulador esquece os Pix em andamento.

## Testar o fluxo inteiro (Postman ou curl)

[`docs/guia-testes-pix.md`](docs/guia-testes-pix.md) traz o roteiro completo: recebimento,
rejeições, envio, replay, estorno, devolução e recusas, com o resultado esperado de cada passo,
em Postman e em curl. A coleção pronta para importar fica em `postman/`:
- `wallet-pix.postman_collection.json`;
- `wallet-pix-local.postman_environment.json`.

Ela roda inteira no Runner e cria clientes novos a cada execução. Também roda pela linha de
comando:

```bash
npx newman run postman/wallet-pix.postman_collection.json -e postman/wallet-pix-local.postman_environment.json
```

## Script de desenvolvimento

`scripts/pixdev.py` usa só a biblioteca padrão do Python:

```bash
python scripts/pixdev.py send --from-account <uuid> --from-taxid <cpf> --to-ispb 87654321 --to-account 001000029 --to-taxid <cpf> --amount 10.00
python scripts/pixdev.py incoming --account 001000029 --taxid 52998224725 --amount 25.00
python scripts/pixdev.py return --e2e <EndToEndId> --amount 5.00
python scripts/pixdev.py messages      # mensagens do SPI simulado
python scripts/pixdev.py events        # PixEvents publicados
```

## Observabilidade

- **Agente OpenTelemetry:** é o mesmo do wallet-core. O `traceparent` viaja como atributo da
  mensagem SNS/SQS, e também atravessa o outbox. O resultado é **um trace só** do
  `/simulate/incoming` até o `holder-check` e o crédito no wallet-core (Jaeger:
  `http://localhost:16686`).
- **Métricas** em `/actuator/prometheus`, com o Prometheus do wallet-core já fazendo scrape do
  `pix-service:8081`:
  - `pix_payments_total{direction,status,reason,ispb}`: cada estado alcançado;
  - `pix_payment_duration_seconds{direction,status}` (histograma): do primeiro ao último estado;
  - `pix_inbound_messages_total{type,result}`, em que uma fatia crescente de `duplicate`
    indica tempestade de reentrega;
  - `pix_outbox_pending` e `pix_queue_messages{queue,state}` (fila do SPI e a DLQ dela);
  - `http_server_requests_seconds` (API de envio) e `http_client_requests_seconds{client_name="wallet-core"}`,
    com histogramas.
- **Dashboard** "Wallet Core — Pix" no Grafana do wallet-core (`http://localhost:3000`, pasta
  Wallet Core). O JSON fica em `../wallet-core/docker/grafana/dashboards/wallet-pix.json`.
  Qualquer mensagem na DLQ fica vermelha no topo, e cada uma precisa de análise.

## Testes

`mvn verify` roda 34 testes:
- domínio;
- casos de uso com fakes;
- contrato JSON;
- regras de arquitetura;
- integração com Postgres real via Testcontainers. Prova o crédito único com 8 entregas
  simultâneas do mesmo ACSC e um único pacs.008 com 6 chamadas simultâneas da mesma chave.

O CI está em `.github/workflows/ci-wallet-pix.yml`.

## Limitações conhecidas

- **Débito órfão:** se o processo cair entre o débito e o registro do pagamento, e o cliente
  nunca repetir a chamada, o débito fica sem Pix. Repetir com a mesma `Idempotency-Key`
  resolve. Falta um job de reconciliação que procure débitos `pix-debit-*` sem pagamento.
- **Fora do escopo:** DICT (chaves Pix), QR Code, MED, envio de devolução (pacs.004 de saída) e
  limites além do valor por transação.
- **Uma devolução por Pix:** a segunda devolução parcial do mesmo Pix é recusada (fica na DLQ).
- **Dados pessoais:** nomes e CPF/CNPJ ficam em claro no banco `pix`. Para produção, criptografar
  essas colunas ou guardar só o necessário para reconciliação.
