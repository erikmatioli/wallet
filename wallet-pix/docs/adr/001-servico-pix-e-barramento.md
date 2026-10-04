# ADR-001 (wallet-pix) — Serviço Pix separado, falando com o SPI por SNS/SQS

**Status:** aceita

## Contexto
A carteira precisa receber e enviar Pix. O fluxo é definido pelo Banco Central (Manual de
Fluxos do Processo de Efetivação do Pix e Catálogo de Mensagens do SPI): mensagens pacs.008
(ordem), pacs.002 (status) e pacs.004 (devolução), trocadas entre os PSPs e o SPI. O
`wallet-core` é o dono do dinheiro (ledger de partidas dobradas, multi-tenant com RLS) e não
deveria conhecer nada de Pix.

## Decisões

### 1. Serviço próprio, fora do wallet-core
O `wallet-pix` é outro projeto do monorepo (ADR-009 do wallet-core): outro deploy, outro
banco (`pix`) e outro ciclo de release (tags `wallet-pix-vX.Y.Z`). O serviço só usa a API
pública do wallet-core. O core ganhou duas capacidades genéricas, sem a palavra "Pix" no domínio:
- a conferência de titularidade (`POST /v1/accounts/holder-check`);
- o estorno de um saque (`POST /v1/transactions/{id}/reversals`).

### 2. O tenant é o participante
Cada tenant do wallet-core tem ISPB próprio. O ISPB endereçado pelo SPI escolhe as credenciais
do tenant (`pix.participants`), e todo acesso ao wallet-core é feito **como esse tenant**, então
a RLS continua isolando os dados. Um Pix entre dois tenants nossos passa pelo serviço dos dois
lados, como pagador e como recebedor, com o mesmo EndToEndId em duas linhas (OUTBOUND e
INBOUND). Por isso o pacs.002 é casado pelo `OrgnlMsgId`, e não só pelo EndToEndId.

### 3. Barramento SNS/SQS, mensagens em JSON espelhando ISO 20022
- **Tópicos SNS com filas SQS assinantes** (fan-out e DLQ de graça), raw delivery.
- **Contrato em JSON com as tags ISO 20022**, e não XML. É legível e fácil de depurar localmente,
  e mapeável para o XML da RSFN num adaptador de borda, sem mudar os casos de uso.
- **AWS SDK v2 direto**, sem Spring Cloud AWS, para não depender da compatibilidade dele com o
  Spring Boot 4.

### 4. Entrega pelo menos uma vez, efeitos exatamente uma vez
| Mecanismo | O que garante |
|---|---|
| Outbox transacional | Uma mensagem existe se e somente se a mudança de estado que a gerou foi commitada |
| `inbound_message` | Deduplicação por id de mensagem, gravada na mesma transação da mudança, com `ON CONFLICT DO NOTHING` |
| Idempotência no wallet-core | Crédito com chave = EndToEndId; débito com `pix-debit-<chave>`; estorno uma vez por saque |
| Versão otimista em `pix_payment` | Duas entregas simultâneas: uma vence, a outra é reentregue e vira duplicata |

As chamadas ao wallet-core ficam **fora** da transação do banco `pix`. Uma falha entre a chamada
e o commit reprocessa a mensagem, e a idempotência do wallet-core devolve a mesma transação.

### 5. Recebimento: validar antes de aceitar, creditar só depois de liquidar
O ACSP sai só depois de o `holder-check` confirmar agência, conta, tipo, status e CPF/CNPJ.
O crédito acontece no ACSC, quando o SPI já liquidou. Os motivos de rejeição (AC03, AC06, AC07,
AC14, BE01) seguem o catálogo e precisam ser conferidos contra a versão vigente.

### 6. Envio: o serviço debita, por API síncrona
> **Revisão (2026-10-04):** a primeira versão desta decisão deixava o débito com quem inicia.
> O iniciador debitava e publicava um `PixPaymentRequested` na fila, e o serviço só creditava
> e validava. Isso deixava a regra "nenhum pacs.008 sai sem débito" fora do serviço, e cada
> iniciador teria de reimplementar o débito, o estorno e as regras de limite. Passou a ser:

1. `POST /v1/pix/payments`, autenticado com o JWT do próprio wallet-core, validado pelo JWKS.
   O token precisa ter o escopo `pix:send`, e o tenant vem do token, nunca do corpo.
2. Cadeia de `PaymentPolicy` (limite por transação hoje; janela de horário e limites diários
   depois), **antes** de qualquer chamada ao wallet-core.
3. O CPF/CNPJ do pagador tem de ser do titular (`holder-check`); o nome vem do wallet-core.
4. Débito por saque, **atômico**, sem consulta prévia de saldo, porque o saldo pode mudar entre
   a consulta e o débito. Recusado → 422 e nada é enviado.
5. Pagamento SENT e pacs.008 no outbox na mesma transação → 202.
6. RJCT → estorno do próprio saque (`/reversals`), não um depósito novo. O vínculo
   débito ↔ estorno fica no ledger.

O token `pix:send` só permite saque e estorno, então vazar o contexto de envio não permite
creditar ninguém.

### 7. Simulador do SPI no repositório
Um simulador pequeno (`spi-simulator`) roteia entre os participantes, faz o papel de um PSP
externo (`99999999`) e permite injetar Pix e devoluções. Sem ele, cada fluxo exigiria disparar
mensagens à mão.

## Alternativas consideradas
| Opção | Por que não |
|---|---|
| Pix dentro do wallet-core | Acopla o core a um arranjo de pagamento específico. Outros arranjos (TED, boleto) repetiriam o padrão |
| Validação combinando `lookup` + `findByTaxId` | Duas chamadas por Pix e quebra com cliente de várias contas. O endpoint dedicado não devolve dados pessoais |
| Iniciador debita e publica na fila (primeira versão) | Ver revisão na seção 6 |
| Consulta de saldo antes do débito | Corrida entre consulta e débito. O débito atômico já recusa o saque a descoberto |
| XML ISO 20022 no barramento local | Mais fiel, mas bem mais verboso de gerar e depurar. Fica para o adaptador da RSFN |
| LocalStack `latest` | Exige token desde 2026. Fixamos a 4.12 community (sem atualizações, só uso local) |

## Consequências
- (+) O wallet-core continua sem conhecer Pix. Os dois endpoints novos são genéricos e servem
  a qualquer arranjo.
- (+) Toda mensagem é reprocessável. Retries e duplicatas não movem dinheiro duas vezes, e há
  testes de integração com concorrência real provando isso.
- (+) Um trace só atravessa API, filas, simulador e wallet-core.
- (−) Débito órfão possível se o processo cair entre o débito e o commit e o cliente não repetir
  a chamada. Falta o job de reconciliação.
- (−) Dados pessoais em claro no banco `pix`.
- Próximos passos: reconciliação de débitos, DICT, adaptador RSFN/XML, devolução de saída,
  limites noturnos e dashboard Grafana do Pix.
