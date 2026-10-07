# ADR-001 (wallet-scheduler) — Serviço de agendamento de transações

**Status:** proposta

## Contexto
O cliente quer escolher a data de uma transferência ou de um Pix e deixar a plataforma executar
no dia, acompanhando numa tela o status de cada agendamento e, quando não deu certo, o motivo
(por exemplo, saldo insuficiente no dia).

O que já existe e o agendador vai usar:
- **wallet-core:** `POST /v1/transfers` (transferência entre contas do mesmo tenant, síncrona e
  idempotente por `Idempotency-Key`) e as consultas de conta.
- **wallet-pix:** `POST /v1/pix/payments` (envio de Pix, idempotente por `Idempotency-Key`). A
  resposta é `SENT`; o resultado final chega depois como `PixEvent` no tópico
  `pix-payment-events`, com o `requestId` igual à `Idempotency-Key` do envio.
- **wallet-console:** console interno de operação, com login por tenant. Não existe login de
  cliente final.

## Decisões

### 1. Projeto próprio no monorepo, em Kotlin
O `wallet-scheduler` é outro projeto do monorepo (ADR-009 do wallet-core): outro deploy, outro
banco (`scheduler`), workflow de CI próprio com filtro de pasta e tags `wallet-scheduler-vX.Y.Z`.
Fala com os demais só pela API pública e pelo barramento, como o wallet-pix.

É escrito em **Kotlin**, por escolha de aprendizado, mantendo o resto do padrão: Maven, Spring
Boot 4, `JdbcClient` (sem JPA), Flyway, testes com JUnit e AssertJ usando fakes em memória (sem
mocks), Testcontainers, ArchUnit e o agente OpenTelemetry.

**Um módulo Maven com pacotes separados**, como o pix-service, e não vários módulos como o
wallet-core. As fronteiras da arquitetura hexagonal são garantidas pelo ArchUnit:

```
br.com.walletscheduler
├── domain          regras e estados; só Kotlin/JDK
├── application     casos de uso e portas (interfaces); sem Spring
├── adapter.in      REST (API do agendamento), consumidor da fila de eventos do Pix, job de execução
├── adapter.out     clientes HTTP do wallet-core e do wallet-pix, persistência JDBC
└── config          ligação com o Spring
```

O primeiro passo é um esqueleto mínimo que compile Kotlin para a JVM 25 com o Spring Boot 4.1,
antes de qualquer regra de negócio: é o principal risco técnico do projeto.

### 2. Escopo do MVP
- **Tipos:** transferência entre contas do mesmo tenant e Pix. **Boleto fica fora**: não existe
  em nenhum projeto e exigiria um serviço próprio, do tamanho do wallet-pix.
- **Pagamento único.** Sem recorrência por enquanto, mas o modelo já separa o agendamento da
  execução (decisão 3) para que a recorrência entre depois sem mudar as tabelas.
- **Cancelamento até a véspera:** até 23:59 do dia anterior, no horário de Brasília. No dia da
  execução o agendamento não pode mais ser cancelado.
- **Sem edição:** para mudar, o cliente cancela e cria outro. Edição fica para depois.
- **Dias não úteis:** não adiam a execução. Transferência interna e Pix funcionam todos os dias.

### 3. Modelo: agendamento, execução e tentativa
- **Agendamento** (`schedule`): a intenção do cliente. Conta pagadora, tipo, destino, valor,
  descrição, data e status (`ATIVO`, `CANCELADO`, `CONCLUIDO`).
- **Execução** (`schedule_execution`): o agendamento rodando num dia. Status `PENDENTE`,
  `EM_PROCESSAMENTO`, `EXECUTADA` ou `FALHOU`, o motivo da falha e o resultado (id da transação
  no core ou EndToEndId do Pix). No MVP há uma execução por agendamento; com recorrência, uma por
  ocorrência.
- **Tentativa** (`schedule_attempt`): cada chamada feita no dia, com horário, resultado, código e
  motivo legível. É o que a tela de detalhe mostra.

```
ATIVO ──(dia chega)──► execução PENDENTE ──► EM_PROCESSAMENTO ──► EXECUTADA ──► agendamento CONCLUIDO
  │                                              │
  │                                              └──(última tentativa recusada)──► FALHOU ──► CONCLUIDO
  └──(cancelado até a véspera)──► CANCELADO
```

### 4. Validar na criação, executar no dia
- **Na criação** (`POST /v1/schedules`, idempotente por `Idempotency-Key`): a conta pagadora
  existe e está ativa; a data é futura (a partir de amanhã); o valor é positivo; o destino é
  válido. Na transferência, a conta de destino existe no tenant. No Pix, o formato dos dados do
  recebedor, e a conferência de titularidade quando o ISPB do recebedor é de um tenant nosso.
- **No dia:** saldo, bloqueios e as políticas de envio do Pix. São regras que só podem ser
  decididas na hora, e quem decide é o serviço que executa (core ou wallet-pix).

### 5. Execução e retentativas no dia
- Três janelas por dia, no horário de Brasília: **06:00, 12:00 e 18:00**. Na primeira o
  agendamento é tentado; se for recusado por um motivo que pode mudar no mesmo dia (saldo
  insuficiente, conta temporariamente bloqueada), é tentado de novo na próxima janela. Depois da
  última, a execução fica `FALHOU` com o motivo da última tentativa.
- Motivos que não mudam no dia (conta destino inexistente, Pix rejeitado pelo recebedor com
  `AC03`, conta pagadora encerrada) falham na hora, sem esperar as outras janelas.
- Um job agendado busca as execuções com tentativa vencida usando `FOR UPDATE SKIP LOCKED`, o
  mesmo padrão do `OutboxRelay` do wallet-core: várias instâncias podem rodar sem executar a
  mesma coisa duas vezes. Não usamos Quartz.

### 6. Exatamente uma vez, mesmo com falhas
- Cada tentativa tem a chave `sched-<id da execução>-<número da tentativa>`, gravada **antes** da
  chamada. Se o serviço cair, ou a chamada der timeout, a nova rodada repete a mesma chave e
  recebe a resposta original (replay) em vez de mover dinheiro duas vezes.
- O resultado de cada chamada é classificado em três casos, com uma `sealed interface`:
  - **executou**: grava o resultado;
  - **recusado**: grava o motivo; tenta na próxima janela ou falha, conforme a decisão 5;
  - **resultado desconhecido** (timeout, 5xx, rede): repete a **mesma** tentativa, com a mesma
    chave, sem gastar uma janela.
- **Transferência:** o resultado do core é síncrono.
- **Pix:** o envio aceito deixa a execução `EM_PROCESSAMENTO`. O agendador assina o tópico
  `pix-payment-events` com uma fila própria (`wallet-scheduler-pix-events`, com DLQ) e fecha a
  execução pelo `requestId` do evento: `PIX_SENT_COMPLETED` vira `EXECUTADA`, `PIX_SENT_REFUNDED`
  vira `FALHOU` com o motivo do SPI. A mensagem é deduplicada pelo id, como no wallet-pix.

### 7. Motivos legíveis
Cada recusa é gravada com o código do serviço que recusou e um texto para o cliente, por exemplo:

| Código | Origem | Texto | Retenta no dia |
|---|---|---|---|
| `INSUFFICIENT_FUNDS` | core / wallet-pix | Saldo insuficiente no momento do pagamento | sim |
| `ACCOUNT_NOT_ACTIVE` | core | Conta pagadora bloqueada ou inativa | sim |
| `DESTINATION_ACCOUNT_NOT_FOUND` | core | Conta de destino não encontrada | não |
| `AC03` | SPI | Conta do recebedor inexistente ou inválida | não |
| código desconhecido | qualquer | Não foi possível concluir o pagamento (código) | não |

### 8. Segurança e tenants
- **Entrada:** a API do agendador valida os JWT emitidos pelo wallet-core (JWKS), como o
  wallet-pix. O tenant vem do token. Dois escopos novos no core: `schedules:read` e
  `schedules:write`, concedidos aos tenants existentes por migration, como foi feito com
  `pix:send` e `pix:receive`.
- **Saída:** o agendador chama o core e o wallet-pix **como o tenant** dono do agendamento, com
  as credenciais do tenant configuradas no serviço, como o wallet-pix já faz por ISPB. A
  transferência exige `ledger:write`.
- **Isolamento:** todas as consultas da API filtram por `tenant_id`. Não usamos Row Level
  Security neste banco porque o job de execução precisa enxergar as execuções de todos os
  tenants; um teste garante que um tenant não lê nem cancela o agendamento de outro.
- **Dados pessoais:** o CPF/CNPJ do recebedor do Pix precisa ser guardado até o dia (o envio
  exige). A API devolve só a forma mascarada. Para produção, criptografar a coluna, como já
  anotado para o banco `pix`.

### 9. Tela no console
Na página da conta, uma seção **Agendamentos**, no mesmo estilo do extrato:
- listagem enxuta: data, tipo, destino, valor e status;
- criação de agendamento (transferência ou Pix) e cancelamento, até a véspera;
- popup de detalhe com tudo o que a API já trouxe: dados do destino, tentativas com horário e
  motivo, e o resultado. Quando executado, mostra o id da transação ou o EndToEndId.

### 10. Observabilidade
- O mesmo agente OpenTelemetry: traces, métricas e logs com `tenant_id` no MDC (também no job e
  no consumidor da fila, que rodam fora de uma requisição).
- Métricas: execuções por resultado (`executada`, `falhou`, por motivo), tentativas por janela e
  atraso do job em relação à janela.

## Ajustes feitos na implementação

- **Nomes em inglês no código:** os status são `ACTIVE`, `CANCELLED`, `COMPLETED` (agendamento),
  `PENDING`, `PROCESSING`, `EXECUTED`, `FAILED`, `CANCELLED` (execução) e `EXECUTED`, `REFUSED`
  (tentativa), como nos outros projetos. A tradução para o cliente fica na tela.
- **Execução cancelada:** além dos estados da decisão 3, a execução ganhou `CANCELLED`, para que o
  job nunca a pegue.
- **Lease da tentativa:** o job toma a execução por 2 minutos (`lease_until`). Resultado
  desconhecido marca a nova olhada para 1 minuto depois. Uma execução `PROCESSING` com lease
  vencido é retomada com a mesma tentativa e a mesma chave.
- **Dia perdido:** se o serviço ficar fora do ar o dia inteiro, a execução falha com `MISSED_DAY`
  em vez de pagar em outro dia. Uma tentativa já em andamento continua sendo consultada depois do
  dia (com a mesma chave), porque só assim se sabe se o dinheiro saiu.
- **Destino da transferência:** informado por agência, número e dígito; na criação o agendador
  busca a conta no core (`GET /v1/accounts/lookup` e depois `GET /v1/accounts/{id}` para o nome
  do titular) e guarda o id da conta, que é o que a transferência usa no dia.
- **Credenciais por tenant:** a criação recusa (`TENANT_NOT_CONFIGURED`) um tenant para o qual o
  serviço não tem credenciais, em vez de descobrir isso no dia.
- **Pix precisa do CPF/CNPJ do pagador:** o envio do wallet-pix exige `payerTaxId` e o confere com
  o titular. O agendamento de Pix pede esse dado, confere na criação com o `holder-check` do core
  (`PAYER_TAX_ID_MISMATCH` se não bater) e guarda até o dia, como o CPF/CNPJ do recebedor.
- **Recebedor do Pix:** só o formato é validado na criação. A conferência de titularidade quando o
  ISPB é de um tenant nosso ficou de fora: o agendador não conhece o mapa ISPB → tenant, e o
  wallet-pix confere o recebedor no envio.
- **Evento antes da resposta:** o simulador do SPI é rápido o bastante para o evento de resultado
  chegar antes de o agendador gravar o `202 SENT`. Nesse caso o evento volta para a fila
  (`NotReadyYetException`, sem apagar a mensagem) e é tratado de novo depois do visibility timeout.
- **Evento perdido:** o Pix enviado espera o evento por 10 minutos (`pix-result-wait`). Depois o job
  chama o wallet-pix de novo com a mesma chave; a resposta (replay) traz o estado atual do Pix
  (`COMPLETED` → executada; `REFUNDED` → falha com o motivo; `SENT` → espera de novo).
- **Execução de Pix rejeitado:** fica `FAILED` com o motivo do SPI e guarda o EndToEndId e o id do
  débito que o wallet-pix estornou, para o cliente rastrear o Pix no extrato.
- **Contrato do evento:** o agendador lê uma cópia dos campos do `PixEvent` em vez de depender do
  módulo `pix-messages`, porque os projetos têm build e release separados.

## Plano por módulo

### wallet-scheduler (novo)
1. **Esqueleto:** `pom.xml` com Kotlin e Spring Boot 4.1 compilando para a JVM 25, aplicação que
   sobe com health check, ArchUnit com as regras de pacote, Dockerfile e `docker-compose.yml`
   na rede do wallet-core, script de criação do banco `scheduler` (como o `pix-db-init`).
2. **Domínio:** agendamento, execução e tentativa com as transições de estado; regra de
   cancelamento até a véspera; janelas do dia; classificação dos motivos.
3. **Aplicação:** criar, listar, detalhar e cancelar agendamentos; executar uma tentativa;
   fechar uma execução pelo evento do Pix. Portas para o core, o wallet-pix, a persistência e o
   relógio.
4. **Adaptadores de saída:** clientes HTTP do core e do wallet-pix com cache de token por tenant
   e o mesmo mapeamento de erros do wallet-pix (rede e 5xx: tentar de novo; 4xx: recusa);
   repositórios JDBC; migrations Flyway.
5. **Adaptadores de entrada:** API REST (`/v1/schedules`), job das janelas, consumidor SQS dos
   eventos do Pix.
6. **Testes:** unitários do domínio e da aplicação com fakes; integração com Postgres e
   LocalStack (Testcontainers) para o job concorrente, o consumidor e o isolamento por tenant.
7. **CI:** `ci-wallet-scheduler.yml` com título do PR, build e testes, e build do Docker.
8. **Docs:** README e guia de testes, e coleção Postman.

### wallet-core
- Escopos `schedules:read` e `schedules:write` em `Tenant.DEFAULT_SCOPES` e numa migration.

### wallet-pix
- Na infraestrutura local (`init-bus.sh`), a fila `wallet-scheduler-pix-events` com DLQ, assinando
  `pix-payment-events`. O código do wallet-pix não muda.

### wallet-console
- Seção Agendamentos na página da conta, com criação, cancelamento e popup de detalhe.

## Ordem de entrega
1. Esqueleto Kotlin rodando no Docker (valida o risco da decisão 1).
2. Agendamento de transferência de ponta a ponta: API, job, janelas, motivos, testes.
3. Agendamento de Pix, com o consumidor de eventos.
4. Tela no console.

## Consequências
- **Positivas:** o agendador não move dinheiro por conta própria; quem valida saldo e regras no
  dia é o core ou o wallet-pix, então as garantias que já existem continuam valendo. A
  idempotência por tentativa torna seguro repetir depois de uma queda. O modelo com execução e
  tentativa já comporta recorrência e deixa o motivo de cada falha visível ao cliente.
- **Negativas:** mais um serviço, banco e fila para operar. Uma segunda linguagem no repositório.
  O serviço guarda credenciais dos tenants e o CPF/CNPJ do recebedor até o dia.
- **Fora do escopo:** boleto, recorrência, edição de agendamento, notificação ao cliente (o
  serviço publicará eventos depois, para quem quiser notificar) e login de cliente final.
