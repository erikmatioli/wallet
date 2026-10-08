# Guia do Desenvolvedor — Wallet Core

Este documento assume que você nunca viu este código antes. Não é referência de API (isso é o [README](../README.md)) nem catálogo de tabelas (isso é o [`data-model.md`](data-model.md)) — é o texto que alguém sentaria do seu lado para explicar, se pudesse. Leia de cima para baixo na primeira vez; depois, use o índice para voltar a uma parte específica.

## Índice

1. [A ideia em uma página](#1-a-ideia-em-uma-página)
2. [Por onde começar a ler o código](#2-por-onde-começar-a-ler-o-código)
3. [Vocabulário que você precisa antes de seguir](#3-vocabulário-que-você-precisa-antes-de-seguir)
4. [`wallet-domain` — as regras, sem framework nenhum](#4-wallet-domain--as-regras-sem-framework-nenhum)
5. [`wallet-application` — os casos de uso](#5-wallet-application--os-casos-de-uso)
6. [`wallet-adapter-out-persistence` — como isso vira SQL](#6-wallet-adapter-out-persistence--como-isso-vira-sql)
7. [`wallet-adapter-in-rest` — a porta HTTP](#7-wallet-adapter-in-rest--a-porta-http)
8. [`wallet-adapter-out-messaging` — publicação de eventos](#8-wallet-adapter-out-messaging--publicação-de-eventos)
9. [`wallet-bootstrap` — onde tudo se conecta](#9-wallet-bootstrap--onde-tudo-se-conecta)
10. [Seguindo uma requisição do início ao fim](#10-seguindo-uma-requisição-do-início-ao-fim)
11. [Tarefas comuns de manutenção](#11-tarefas-comuns-de-manutenção)
12. [Testes: o que existe e por que](#12-testes-o-que-existe-e-por-quê)
13. [Erros comuns e onde procurar](#13-erros-comuns-e-onde-procurar)

---

## 1. A ideia em uma página

Este sistema é o **core de uma carteira digital**: ele cadastra clientes, abre uma conta de pagamento para cada um (no formato que o Banco Central usa), e controla o saldo dessa conta através de um **livro-razão** (ledger) de lançamentos contábeis — nunca através de um campo `saldo` que alguém dá `UPDATE` direto.

A regra que never pode quebrar: **dinheiro nunca é criado nem destruído, só se move entre contas**. Todo depósito, saque ou transferência gera pelo menos dois lançamentos (um débito, um crédito) que somam zero. Isso é chamado de **partida dobrada** (double-entry bookkeeping) e é a mesma técnica que bancos de verdade usam há séculos — não é invenção deste projeto.

A arquitetura é **hexagonal** (ports & adapters): o núcleo (`wallet-domain` + `wallet-application`) não sabe que existe Spring, banco de dados ou HTTP. Ele só conhece interfaces (“portas”). Quem implementa essas portas com tecnologia de verdade são os **adapters**, e quem liga tudo isso é o `wallet-bootstrap`. Isso existe por um motivo prático, não estético: as regras de negócio mais críticas do sistema (como calcular um saldo, como validar um CPF) podem ser testadas em milissegundos, sem subir um banco, sem subir o Spring — e ficam impossíveis de contaminar com detalhe de infraestrutura sem querer.

```
                       ┌──────────────────────────── wallet-bootstrap ────────────────────────────┐
                       │  liga tudo: @SpringBootApplication, UseCaseConfig, application.yml         │
                       └───────────────────────────────────────────────────────────────────────────┘
                                   │                        │                          │
         ┌─────────────────────────▼───────┐   ┌────────────▼─────────────┐  ┌─────────▼────────────────┐
         │  wallet-adapter-in-rest          │   │  wallet-application      │  │ wallet-adapter-out-*      │
         │  Controllers, segurança (JWT)    │──▶│  Casos de uso (portas)   │◀─│ persistence: JDBC/Flyway  │
         └──────────────────────────────────┘   └────────────┬──────────────┘  │ messaging: publisher      │
                                                             │                └───────────────────────────┘
                                                ┌────────────▼─────────────┐
                                                │  wallet-domain            │
                                                │  Java puro: Money, Account,│
                                                │  LedgerTransaction, etc.   │
                                                └───────────────────────────┘
```

As setas sempre apontam para dentro. `wallet-domain` não depende de nada do projeto. `wallet-application` só depende de `wallet-domain`. Os adapters dependem de `wallet-application`, nunca uns dos outros diretamente. Essa regra é verificada automaticamente pelo `ArchitectureTest` (seção 12) — se você quebrar essa direção, o build falha, não é só um "por favor não faça isso" em comentário.

## 2. Por onde começar a ler o código

Não comece pelo `WalletCoreApplication.java` (é só um `main()`, não ensina nada). A ordem que realmente ensina o sistema:

1. **`wallet-domain/.../shared/Money.java`** — o tipo mais usado no projeto inteiro, e o mais simples.
2. **`wallet-domain/.../ledger/LedgerTransaction.java`** — o coração conceitual: o que é uma transação balanceada.
3. **`wallet-application/.../service/MoveMoneyService.java`** — o caso de uso mais importante, onde a teoria vira código que mexe em banco de dados.
4. **`wallet-adapter-out-persistence/.../JdbcAccountRepository.java`**, método `applyDelta` — onde a concorrência é realmente resolvida (um `UPDATE` SQL).
5. **`wallet-adapter-in-rest/.../AccountController.java`** — onde isso tudo vira uma resposta HTTP.

Depois desses cinco arquivos, o resto do sistema é variação sobre o mesmo padrão. As seções 4 a 9 abaixo seguem essa mesma lógica: primeiro o que é mais central, depois o que é mais periférico.

## 3. Vocabulário que você precisa antes de seguir

Se algum desses termos for novo, vale ler esta seção com calma — o resto do documento assume que você já sabe o que eles significam.

- **Record** (`record Money(long cents) {}`) — um recurso do Java (desde a versão 16) para criar uma classe imutável de dados com uma linha: gera automaticamente o construtor, os getters (`money.cents()`, sem `get`), `equals`, `hashCode` e `toString`. Quase todo tipo em `wallet-domain` é um record.
- **Sealed interface** (`sealed interface DomainEvent permits CustomerOnboarded, TransactionPosted`) — declara explicitamente "só estas classes podem implementar esta interface". Combinado com `switch`, o compilador consegue avisar se você esqueceu de tratar um caso novo.
- **Porta (port)** — uma interface que descreve *o que* o sistema precisa fazer, sem dizer *como*. `TransactionRunner` é uma porta: "execute este trabalho dentro de uma transação". Quem implementa é um adapter.
- **Adapter** — uma implementação concreta de uma porta, amarrada a uma tecnologia específica. `JdbcTransactionRunner` é o adapter de `TransactionRunner` que usa PostgreSQL via JDBC.
- **Partida dobrada (double-entry)** — cada movimento de dinheiro é registrado como pelo menos dois lançamentos (uma perna de débito, uma de crédito) cuja soma é zero. Ver seção 4.5.
- **Idempotência** — repetir a mesma requisição (com a mesma `Idempotency-Key`) não deve produzir o efeito duas vezes. Ver seção 5.2 (`MoveMoneyService`).
- **Row Level Security (RLS)** — um recurso do PostgreSQL que filtra automaticamente as linhas visíveis numa consulta, com base numa configuração da sessão (`app.tenant_id`, neste projeto). É como o isolamento entre tenants é garantido no nível do banco, não só no código Java.
- **Outbox pattern** — gravar um evento na mesma transação do fato que ele representa (numa tabela `outbox_event`), e publicá-lo depois, de forma assíncrona. Evita o problema de "salvei o dado mas esqueci de publicar o evento" (ou vice-versa).

## 4. `wallet-domain` — as regras, sem framework nenhum

Pacote raiz: `br.com.walletcore.domain`. Nenhuma classe aqui importa Spring, JDBC ou qualquer coisa fora do JDK. Se você tentar importar algo assim, o `ArchitectureTest` quebra o build.

### 4.1 `shared/` — os tipos que todo mundo usa

- **`Money`** — um valor em reais guardado como `long cents` (nunca `double`/`float`: ponto flutuante binário não representa dinheiro exatamente). `Money.ofDecimal(new BigDecimal("10.50"))` converte de decimal para centavos e valida que não tem mais de 2 casas. Métodos `plus`/`minus`/`negate` fazem aritmética exata (usam `Math.addExact` etc., que lançam exceção em overflow em vez de dar resultado errado silenciosamente).
- **`UuidV7`** — gera UUIDs ordenáveis por tempo (RFC 9562 v7), em vez do `UUID.randomUUID()` padrão (que é v4, totalmente aleatório). Isso importa para performance de índice: inserir UUIDs aleatórios num índice B-tree do Postgres espalha as escritas por toda a árvore; UUIDs v7 mantêm as escritas concentradas no "final" da árvore, como um auto-incremento.
- **`TenantId`, `CustomerId`, `AccountId`, `TransactionId`** — cada um é um record que embrulha um `UUID`. Por que não usar `UUID` puro em todo lugar? Para o compilador impedir você de passar um `AccountId` onde um `CustomerId` era esperado — são tipos diferentes, mesmo carregando o mesmo formato de dado por baixo. `TenantId` é o único que tem um método extra, `TenantId.of(String)`, usado para parsear o `tenant_id` que vem de dentro do JWT.

### 4.2 `tenant/Tenant.java`

Representa o cliente white-label (quem contrata a plataforma, não o cliente final). O método estático `Tenant.create(...)` é o único jeito de construir um `Tenant` novo — ele valida formato de `clientId`, `ispb`, `branch` antes de deixar o objeto existir. Isso é um padrão que se repete em todo o domínio: **construtores privados/validação centralizada em fábricas estáticas**, para que seja impossível ter um `Tenant` inválido "solto" no sistema.

### 4.3 `customer/`

- **`TaxId`** — CPF ou CNPJ, com validação completa do dígito verificador (inclusive o formato alfanumérico de CNPJ que a Receita Federal introduziu). `TaxId.parse("529.982.247-25")` remove a máscara, valida, e devolve um `TaxId` com `type()` = `CPF` ou `CNPJ`. O método `masked()` devolve só os 4 últimos dígitos — é o que aparece em log, nunca o documento completo.
- **`Customer`** — o cliente final. `Customer.onboard(...)` é a fábrica: recebe tenant, nome, `TaxId` já validado, e devolve um `Customer` novo com status `ACTIVE`.

### 4.4 `account/`

- **`AccountType`** — hoje só tem um valor, `PAYMENT` (código BCB `TRAN`, conta de pagamento). É um enum e não uma constante solta porque o dia que existir um segundo tipo de conta, o compilador força você a decidir o que fazer em cada `switch` que usa isso.
- **`PaymentAccountNumber`** — ISPB + agência + número + dígito verificador + tipo. `PaymentAccountNumber.generate(ispb, branch, sequence, type)` monta o número a partir de uma sequência numérica (que vem do banco, ver seção 6) e calcula o dígito com módulo 11. **Atenção:** o Banco Central não define um algoritmo único de dígito — cada instituição usa o seu. Se isso for para produção de verdade, o algoritmo em `computeCheckDigit` precisa ser substituído pelo que a sua instituição realmente usa.
- **`Account`** — não é só a conta do cliente. Repare no campo `kind`: `CUSTOMER` (conta de um cliente, nunca pode ficar negativa) ou `SETTLEMENT` (conta interna, pode ficar negativa, existe só para servir de contrapartida contábil de depósitos/saques — ver seção 4.5). `Account.openPayment(...)` e `Account.openSettlement(...)` são as duas fábricas, uma para cada tipo.

### 4.5 `ledger/` — o mais importante do domínio inteiro

- **`EntryDirection`** — `DEBIT` ou `CREDIT`. `CREDIT` aumenta o saldo, `DEBIT` diminui.
- **`TransactionType`** — `DEPOSIT`, `WITHDRAWAL`, `TRANSFER` e os tipos Pix (`PIX_IN`, `PIX_OUT`, `PIX_REFUND`, `PIX_RETURN_IN`, `PIX_RETURN_OUT`), cada um com uma linha de detalhe em `pix_transaction_detail` (ver ADR-010).
- **`Leg`** — uma "perna" de uma transação: uma conta, uma direção, um valor (sempre positivo — o sinal vem da direção, não do valor).
- **`LedgerTransaction`** — **esta é a classe para entender de verdade antes de mexer em qualquer coisa relacionada a dinheiro.** O construtor dela (o bloco compacto `public LedgerTransaction { ... }`) valida, na hora da criação:
  - tem pelo menos 2 pernas;
  - as pernas são de contas diferentes (não dá para debitar e creditar a mesma conta);
  - **a soma dos débitos é igual à soma dos créditos** — essa é a regra de partida dobrada, e ela é impossível de violar em memória: se você tentar criar um `LedgerTransaction` desbalanceado, ele lança `BusinessRuleException` na hora, antes de qualquer coisa tocar o banco.

  As três fábricas explicam o desenho contábil:
  - `LedgerTransaction.deposit(...)` — débito na conta de **settlement**, crédito na conta do **cliente**. Isto é: quando dinheiro "entra" na plataforma, ele sai de uma conta interna e vai para o cliente — nunca é criado do nada.
  - `LedgerTransaction.withdrawal(...)` — o inverso: débito no cliente, crédito no settlement.
  - `LedgerTransaction.transfer(...)` — débito na origem, crédito no destino, ambas contas de cliente.

  O método `legsInLockOrder()` ordena as pernas por `accountId` — isso é o que garante que uma transferência A→B e outra B→A, acontecendo ao mesmo tempo, sempre travam as contas na mesma ordem (a de menor id primeiro), o que torna deadlock matematicamente impossível. Guarde esse detalhe; ele volta a aparecer na seção 6.

- **`LedgerEntry`** — diferente de `LedgerTransaction` (que é a intenção validada em memória), este é o **fato gravado** no banco: uma linha imutável, com `sequence` (posição dessa entrada na história daquela conta especificamente, sem buracos) e `balanceAfter` (o saldo logo depois desse lançamento). É por causa desses dois campos que o saldo pode ser reconstruído do zero, só lendo os `LedgerEntry` em ordem — ver `AuditLedgerService` na seção 5.4.

### 4.6 `event/`

- **`DomainEvent`** — interface sealed, só duas implementações permitidas: `CustomerOnboarded` e `TransactionPosted`. Repare que os campos desses eventos são só identificadores e números — nunca CPF, nunca nome. Isso é proposital: o evento pode acabar sendo consumido por um sistema externo, fora do perímetro de segurança do banco.

### 4.7 `exception/`

Cinco classes: `DomainException` (base, carrega um `code` estável tipo `"INSUFFICIENT_FUNDS"`) e quatro subclasses (`ValidationException`, `NotFoundException`, `ConflictException`, `BusinessRuleException`). O `code` de cada uma é o que a API HTTP transforma em corpo de erro (`ApiExceptionHandler`, seção 7) — se você lançar uma exceção nova, o `code` que você escolher é o que o cliente da API vai ver.

---

## 5. `wallet-application` — os casos de uso

Pacote raiz: `br.com.walletcore.application`. Ainda sem framework — os `service/*.java` são classes Java comuns, instanciadas manualmente pelo `UseCaseConfig` (seção 9), não por `@Service` do Spring.

### 5.1 Como o pacote está organizado

- **`port/in/`** — uma interface por caso de uso (o que o "mundo de fora" pode pedir ao sistema). Ex.: `MoveMoneyUseCase`.
- **`port/out/`** — uma interface por dependência externa que um caso de uso precisa (banco, hash de senha, publicação de evento, métricas). Ex.: `AccountRepository`.
- **`service/`** — as implementações dos casos de uso, que dependem só das portas `out`, nunca de uma tecnologia concreta.

Cada porta `in` costuma declarar, dentro dela mesma, os records de `Command`/`Result` daquele caso de uso — por exemplo, `MoveMoneyUseCase.DepositCommand` é um record aninhado dentro da interface `MoveMoneyUseCase`. Isso mantém "o que esse caso de uso recebe e devolve" no mesmo arquivo que "o que esse caso de uso faz".

### 5.2 `MoveMoneyService` — deposit / withdraw / transfer

Este é o serviço mais denso do projeto. Ele implementa `MoveMoneyUseCase` (três métodos: `deposit`, `withdraw`, `transfer`) e todos os três convergem para um único método privado, `post(...)`. Ler `post()` é ler o algoritmo inteiro:

```java
private TransactionResult post(LedgerTransaction lt, String key, String fingerprint, AccountId settlementId) {
    // 1. idempotência: essa chave já foi usada?
    Optional<StoredTransaction> existing = journal.insertIfAbsent(lt, key, fingerprint);
    if (existing.isPresent()) {
        // já existe: ou é um replay (devolve o resultado antigo) ou é conflito (chave reusada com payload diferente → 409)
        ...
    }
    // 2. para cada perna da transação, em ordem determinística de conta (legsInLockOrder):
    for (Leg leg : lt.legsInLockOrder()) {
        // aplica o delta atomicamente no banco (ver JdbcAccountRepository.applyDelta, seção 6)
        BalanceUpdateResult result = accounts.applyDelta(...);
        // sucesso vira um LedgerEntry; rejeição (saldo insuficiente, conta inativa...) lança exceção e desfaz tudo
    }
    // 3. grava os lançamentos (append-only) e enfileira o evento no outbox
    ledger.append(entries);
    outbox.enqueue(...);
    metrics.transactionPosted(...);
    return ...;
}
```

Tudo isso roda dentro de **uma única transação de banco** (`tx.inTransaction(...)`, chamado por quem invoca `deposit`/`withdraw`/`transfer`) — se qualquer passo falhar, tudo é desfeito, nunca existe uma transação "meio postada".

Dois detalhes que vale entender bem:

- **`fingerprint(...)`** — um hash SHA-256 dos parâmetros do pedido (conta, valor, tipo, descrição). Serve para diferenciar "o cliente repetiu a mesma requisição de propósito" (mesma `Idempotency-Key` + mesmo fingerprint → devolve o resultado antigo, sem mexer em saldo de novo) de "o cliente reusou uma chave para um pedido diferente por engano" (mesma chave, fingerprint diferente → `409 IDEMPOTENCY_KEY_REUSED`).
- **`rejection(...)`** — traduz um `BalanceUpdateResult.Rejected` (que vem do banco, seção 6) para a exceção de domínio certa, e é aqui que a métrica `transactionRejected` é registrada — então toda rejeição, de qualquer um dos três métodos públicos, passa por este único ponto.

### 5.3 `OnboardCustomerService`

Mais simples que o anterior: valida o `TaxId`, confere que o tenant existe e está ativo, confere que não existe outro cliente com o mesmo documento, gera o número da conta (`accounts.nextAccountSequence()` + `PaymentAccountNumber.generate`), insere cliente e conta, enfileira o evento `CustomerOnboarded`, registra a métrica. Tudo dentro de uma transação.

### 5.4 `AuditLedgerService` — o replay

Implementa o "recriar o saldo a partir dos eventos" que é a proposta central do projeto. Percorre (`ledger.forEachInSequence`) todos os `LedgerEntry` de uma conta, em ordem, e para cada um:
1. Confere que `sequence` é exatamente o próximo esperado (sem buraco).
2. Soma o `signedCents()` daquele lançamento a um saldo "replay" que começa em zero.
3. Confere que esse saldo replay bate com o `balanceAfter` gravado naquele lançamento.

No final, compara o saldo replay acumulado com `account.balance()` (o que está gravado na projeção) e a última sequência com `account.version()`. Qualquer divergência vira uma string na lista `findings`; se a lista estiver vazia, `consistent = true`. É esse relatório que tanto o endpoint `GET /v1/accounts/{id}/audit` quanto o `AuditSweepJob` (seção 9) consomem.

### 5.5 `QueryAccountService`, `ProvisionTenantService`, `SettlementRouter`

- **`QueryAccountService`** — só leitura (`tx.readOnly`, nunca `inTransaction`). O método privado `load()` é reusado tanto por `getAccount` quanto por `getStatement`, e filtra `kind == CUSTOMER` — contas de settlement nunca são visíveis pela API, mesmo que alguém adivinhe o UUID.
- **`ProvisionTenantService`** — cria um tenant novo e abre N contas de settlement para ele (`settlementShards`, tipicamente 4). Usado hoje só pelo `DevDataSeeder` (seção 9); não existe endpoint HTTP para isso ainda (ver README, "fora do escopo").
- **`SettlementRouter`** — escolhe qual das N contas de settlement de um tenant vai ser a contrapartida de um depósito/saque específico, usando o hash do `TransactionId` (`transactionId.value().hashCode() % número de shards`). Existe para que um tenant com muito tráfego não sirialize todos os depósitos numa única linha de banco (que é exatamente o gargalo que uma única conta de settlement teria). Mantém um cache em memória (`ConcurrentHashMap`) dos ids de settlement por tenant, para não consultar o banco a cada transação.

---

## 6. `wallet-adapter-out-persistence` — como isso vira SQL

Pacote raiz: `br.com.walletcore.adapter.out.persistence`. Aqui sim tem Spring (`@Component`, `@Repository`) e JDBC. Cada classe implementa uma porta `out` de `wallet-application`.

### 6.1 `JdbcTransactionRunner` — o mais importante deste módulo

Implementa `TransactionRunner`. Toda vez que um serviço de aplicação chama `tx.inTransaction(tenantId, () -> ...)` ou `tx.readOnly(...)`, é este código que roda. Três responsabilidades numa classe só:

1. **Isolamento por tenant via Row Level Security**: antes de rodar o trabalho, `bindTenant(tenantId)` executa `SELECT set_config('app.tenant_id', ...)` — é essa configuração de sessão que as políticas de RLS do Postgres leem (ver `data-model.md`, seção 7). Sem essa linha, nenhuma linha das tabelas protegidas fica visível.
2. **Retry de falha transitória**: `withRetry(...)` reexecuta a transação inteira (não só a query que falhou) até 4 vezes, com backoff exponencial + jitter, se o Postgres sinalizar `TransientDataAccessException` (deadlock, timeout de lock, blip de conexão). Funciona porque toda a lógica de dentro é idempotente — reexecutar `bindTenant` + o trabalho do zero não tem efeito colateral extra.
3. **Observabilidade**: `observed(...)` embrulha tudo num `Observation` do Micrometer, que vira ao mesmo tempo um span de trace e um timer/counter de métrica — ver `docs/adr/007-observabilidade.md` se quiser o porquê dessa escolha.

### 6.2 `JdbcAccountRepository.applyDelta(...)` — onde a concorrência é resolvida de verdade

Este método é a resposta prática para "como garantir que duas movimentações simultâneas na mesma carteira não se atropelam". Uma única instrução SQL:

```sql
UPDATE account
   SET balance_cents = balance_cents + :delta,
       version        = version + 1,
       updated_at     = now()
 WHERE tenant_id = :tenant
   AND id         = :id
   AND kind        = :kind
   AND status      = 'ACTIVE'
   AND (allow_negative OR balance_cents + :delta >= 0)
RETURNING balance_cents, version
```

O PostgreSQL trava a linha durante esse `UPDATE`; se outra transação tentar o mesmo `UPDATE` ao mesmo tempo, ela **espera** e, quando a primeira commitar, reavalia o `WHERE` contra o valor já atualizado — então nunca acontece de duas movimentações lerem o mesmo saldo antigo e "perderem" uma atualização uma da outra (o clássico *lost update*). Se o `WHERE` não bater (saldo insuficiente, conta inativa, conta não existe), a query não atualiza nenhuma linha, e o método volta a consultar (`findById`) só para decidir qual dos três motivos foi — isso vira um `BalanceUpdateResult.Rejected` com o motivo certo.

O `version` retornado por essa query é exatamente o `sequence` que o `LedgerEntry` daquela movimentação vai usar (ver `MoveMoneyService.post`, seção 5.2) — é assim que a sequência por conta nunca tem buraco, mesmo sob concorrência.

### 6.3 As outras classes `Jdbc*Repository`

Seguem todas o mesmo padrão: implementam uma porta `out`, usam `JdbcClient` (a API moderna do Spring para SQL explícito, sem ORM) com métodos `.sql(...).param(...).query(...)`. Vale destacar:

- **`JdbcLedgerRepository.forEachInSequence(...)`** — não carrega todos os lançamentos de uma conta de uma vez em memória; pagina em blocos de 1000 (`REPLAY_CHUNK`), o que é o que permite o `AuditLedgerService` (seção 5.4) rodar mesmo numa conta com histórico grande, sem estourar memória.
- **`JdbcTransactionJournal.insertIfAbsent(...)`** — é aqui que a idempotência vira SQL: `INSERT ... ON CONFLICT (tenant_id, idempotency_key) DO NOTHING`. Se duas requisições com a mesma chave chegarem ao mesmo tempo, a segunda espera a primeira commitar (o índice único força isso) e depois lê o que a primeira gravou.
- **`JdbcTenantRepository`** — a única que não passa por Row Level Security (a tabela `tenant` não tem RLS, porque ela é a raiz da hierarquia — não existe "tenant do tenant" para filtrar por). Tem o método `findAllActiveIds()`, usado pelo `AuditSweepJob` (seção 9) para saber quais tenants varrer.

### 6.4 `OutboxRelay` — publicando os eventos gravados

Roda a cada 500ms (`@Scheduled`), pega até 100 linhas de `outbox_event` ainda não publicadas (`FOR UPDATE SKIP LOCKED` — permite mais de uma instância do relay rodar em paralelo sem disputar a mesma linha), entrega cada uma ao `EventPublisher` (seção 8), e marca `published_at`. Mantém um `Gauge` (`wallet.outbox.pending`) com a contagem de pendências, para alertar se esse número crescer sem parar.

### 6.5 A migration (`V1__core_schema.sql`)

Não repito aqui — está inteiramente documentada em [`data-model.md`](data-model.md), que é o lugar certo para consultar quando a dúvida for "que coluna existe em tal tabela" ou "por que essa constraint existe".

---

## 7. `wallet-adapter-in-rest` — a porta HTTP

Pacote raiz: `br.com.walletcore.adapter.in.rest`. É aqui que uma requisição HTTP vira uma chamada a um caso de uso, e vice-versa.

### 7.1 `security/` — Basic para pegar token, JWT para tudo mais

- **`SecurityConfig`** — define duas cadeias de filtro. A primeira (`tokenEndpointChain`) protege só `/v1/auth/token` com HTTP Basic. A segunda (`apiChain`) protege todo o resto com Bearer JWT, e declara, rota por rota, qual *scope* é necessário (`hasAuthority("SCOPE_ledger:write")`, por exemplo). Qualquer rota não listada explicitamente é negada (`anyRequest().denyAll()`) — uma rota nova só fica acessível se alguém adicionar a linha aqui de propósito.
- **`TenantUserDetailsService`** + **`TenantPrincipal`** — o Spring Security precisa de um `UserDetails` para validar o Basic; `TenantPrincipal` embrulha um `Tenant` do domínio para servir esse papel, sem que o domínio precise saber que Spring Security existe.
- **`PasswordEncoderSecretHasher`** — implementa a porta `SecretHasher` (de `wallet-application`) usando `PasswordEncoder` do Spring (bcrypt). É por isso que `wallet-application` consegue "pedir para hashear um segredo" sem importar Spring Security.
- **`JwtConfig`** — gera (ou carrega de arquivo PEM, se configurado) o par de chaves RSA usado para assinar os JWTs, e monta os beans `JwtEncoder`/`JwtDecoder`. **Sem uma chave configurada, ele gera uma chave efêmera a cada start e avisa em log** — isso é só para desenvolvimento; em produção as chaves precisam vir de um secret manager.
- **`TokenController`** — dois endpoints: `POST /v1/auth/token` (autenticado por Basic, gera o JWT com `tenant_id` e `scope` como claims) e `GET /.well-known/jwks.json` (expõe a chave pública, para quem quiser validar o token de forma independente).

### 7.2 Os controllers de negócio

- **`CustomerController`** — um único endpoint, `POST /v1/customers`, que chama `OnboardCustomerUseCase`.
- **`AccountController`** — `GET /balance`, `GET /statement`, `POST /deposits`, `POST /withdrawals`, `GET /audit`. Repare no método estático `respond(TransactionResult)`, compartilhado com `TransferController` — ele decide entre `201 Created` (transação nova) e `200 OK` + header `Idempotent-Replayed: true` (transação repetida).
- **`TransferController`** — `POST /v1/transfers`. Valida que o corpo trouxe exatamente um jeito de identificar o destino (`destinationAccountId` OU `destination` por número, nunca os dois, nunca nenhum).
- **`CurrentTenant`** — uma classe utilitária de uma linha: extrai o `TenantId` do claim `tenant_id` dentro do JWT já validado. Sempre é o JWT que decide o tenant, nunca um parâmetro da requisição — é assim que fica impossível um tenant acessar dados de outro só trocando um id na URL.

### 7.3 `ApiExceptionHandler`

Um `@RestControllerAdvice` central: traduz cada `DomainException` para o HTTP status certo (`NotFoundException` → 404, `ConflictException` → 409, `BusinessRuleException` → 422, `ValidationException` → 400), usando sempre o formato `application/problem+json` (RFC 9457) com um campo `code` estável. Se você criar uma exceção de domínio nova, ela já cai automaticamente num desses baldes pelo `switch` no topo do handler — só authorize um `code` novo, não precisa mexer neste arquivo.

### 7.4 `TenantLoggingInterceptor` + `WebMvcConfig`

Depois que o JWT já foi validado, este interceptor coloca `tenant_id` no MDC do SLF4J (o mecanismo do log que permite anexar contexto extra a cada linha). Roda como `HandlerInterceptor` — não como `Filter` de servlet — de propósito, para não competir em ordem com a cadeia de filtros do Spring Security. `WebMvcConfig` é só o registro desse interceptor.

---

## 8. `wallet-adapter-out-messaging` — publicação de eventos

O módulo mais simples do projeto: uma classe só, **`LoggingEventPublisher`**, que implementa `EventPublisher` logando a mensagem em vez de mandar para um broker de verdade. É o ponto de extensão óbvio quando alguém quiser plugar Kafka/SNS/SQS — troca essa implementação, nada mais no sistema muda, porque tudo o mais fala com a porta `EventPublisher`, não com esta classe.

---

## 9. `wallet-bootstrap` — onde tudo se conecta

Pacote raiz: `br.com.walletcore.bootstrap` (mais a classe solta `WalletCoreApplication` em `br.com.walletcore`).

- **`WalletCoreApplication`** — o `@SpringBootApplication` de sempre. `@EnableScheduling` está aqui porque o `OutboxRelay` e o `AuditSweepJob` usam `@Scheduled`.
- **`UseCaseConfig`** — **este arquivo é o que literalmente transforma os serviços "puros" de `wallet-application` em beans Spring.** Cada `@Bean` chama o construtor de um `XxxService` passando as portas que o Spring já injetou (que por sua vez são os adapters JDBC/REST). Se você criar um caso de uso novo, é aqui que ele precisa ser registrado.
- **`DevDataSeeder`** — só ativo no profile `dev` (`@Profile("dev")`). Roda uma vez na inicialização e cria o tenant `demo-tenant` (via `ProvisionTenantUseCase`) se ele ainda não existir — é de onde vêm as credenciais que você usa para testar localmente.
- **`observability/MicrometerMetricsRecorder`** — implementa a porta `MetricsRecorder` usando `Counter` do Micrometer. Cada método (`transactionPosted`, `transactionRejected`, `customerOnboarded`, `auditCompleted`) vira um contador Prometheus com as tags certas.
- **`observability/AuditSweepJob`** — roda a cada 5 minutos, itera os tenants ativos (`TenantRepository.findAllActiveIds()`) e, para cada um, reaudita uma amostra das contas mais recentemente movimentadas (`AccountRepository.findRecentlyActiveCustomerAccountIds`), chamando `AuditLedgerUseCase.audit(...)` — o mesmo caso de uso que o endpoint HTTP usa. Qualquer inconsistência vira log `ERROR` + métrica, que é o que os alertas do Prometheus (`docker/prometheus/alerts.yml`) observam.
- **`ArchitectureTest`** e **`WalletCoreConcurrencyTest`** — ver seção 12.

---

## 10. Seguindo uma requisição do início ao fim

A melhor forma de realmente entender o sistema é seguir um `POST /v1/accounts/{id}/deposits` classe por classe:

1. **`SecurityConfig`** já validou o JWT antes de qualquer controller rodar (é um filtro, roda antes do Spring MVC despachar a requisição).
2. **`TenantLoggingInterceptor.preHandle`** coloca `tenant_id` no MDC de log.
3. **`AccountController.deposit(...)`** recebe a requisição, extrai o tenant via **`CurrentTenant.from(jwt)`**, converte o `BigDecimal` do JSON para **`Money.ofDecimal(...)`**, monta um **`MoveMoneyUseCase.DepositCommand`** e chama `moveMoney.deposit(command)`.
4. **`MoveMoneyService.deposit(...)`** valida a `Idempotency-Key` e o valor, calcula o `fingerprint`, escolhe uma conta de settlement via **`SettlementRouter.pick(...)`**, monta um **`LedgerTransaction.deposit(...)`** (que já valida sozinho que está balanceado) e chama `tx.inTransaction(...)`.
5. **`JdbcTransactionRunner.inTransaction(...)`** abre a transação, chama `bindTenant` (RLS), e roda o trabalho dentro de retry + Observation.
6. Dentro da transação, `post(...)` chama **`JdbcTransactionJournal.insertIfAbsent(...)`** (idempotência), depois, para cada perna, **`JdbcAccountRepository.applyDelta(...)`** (o `UPDATE` atômico da seção 6.2).
7. Ainda dentro da mesma transação: **`JdbcLedgerRepository.append(...)`** grava os `LedgerEntry`, **`JdbcOutboxRepository.enqueue(...)`** grava o evento `TransactionPosted` na tabela `outbox_event`.
8. A transação commita. Fora dela, `MicrometerMetricsRecorder.transactionPosted(...)` já foi chamado (dentro do `post()`, mas o efeito — incrementar um contador em memória — não depende de transação de banco).
9. `AccountController.respond(...)` monta o `201 Created` com o corpo `TransactionResponse`.
10. Em paralelo, sem bloquear a resposta acima: **`OutboxRelay`** (rodando no seu próprio ciclo de `@Scheduled`) eventualmente pega essa linha do outbox e chama **`LoggingEventPublisher.publish(...)`**.

Se você entender essas dez etapas, você entende a espinha dorsal do sistema inteiro — todo o resto (onboarding, transferência, consulta de saldo) é uma variação bem próxima disso.

## 11. Tarefas comuns de manutenção

**Adicionar um campo novo em `Customer`:**
1. Adicione o campo no record `Customer` (`wallet-domain`) e ajuste `Customer.onboard(...)` para validá-lo.
2. Adicione a coluna numa migration Flyway **nova** (`V2__...sql` — nunca edite `V1` depois que ela já rodou em algum ambiente).
3. Ajuste `JdbcCustomerRepository.insert(...)` e o `map(...)` de leitura, se houver.
4. Se o campo deve aparecer na API, ajuste `ApiModels.CustomerResponse` e o `CustomerController`.

**Adicionar um endpoint novo:**
1. Se for um caso de uso novo: crie a porta `in` em `wallet-application/port/in/`, implemente em `service/`, registre em `UseCaseConfig`.
2. Crie/ajuste o `@RestController`, adicione o record de request/response em `ApiModels`.
3. Adicione a rota e o `scope` necessário em `SecurityConfig.apiChain(...)` — sem isso, a rota fica `403` por padrão (`anyRequest().denyAll()`).

**Adicionar uma métrica de negócio nova:**
1. Adicione o método na porta `MetricsRecorder` (`wallet-application`).
2. Implemente em `MicrometerMetricsRecorder` (`wallet-bootstrap`).
3. Chame esse método no serviço de aplicação certo, no ponto exato onde o fato acontece.

**Investigar por que um teste de concorrência falhou:**
Comece por `WalletCoreConcurrencyTest` (seção 12) — o nome do método já diz o que ele testa. Se falhou, o próximo passo quase sempre é olhar `JdbcAccountRepository.applyDelta` (a query que devia ter travado a linha) ou `LedgerTransaction.legsInLockOrder()` (a ordem de lock que devia evitar o deadlock).

## 12. Testes: o que existe e por quê

- **`wallet-domain/src/test`** — testes unitários puros (`MoneyTest`, `TaxIdTest`, `PaymentAccountNumberTest`, `LedgerTransactionTest`). Rodam em milissegundos, sem nenhuma dependência externa.
- **`wallet-application/src/test`** — **`InMemoryFixture`** é a peça central: implementa todas as portas `out` (`AccountRepository`, `LedgerRepository`, etc.) com `HashMap`/`List` em memória, em vez de banco de verdade. `MoveMoneyServiceTest` usa esse fixture para testar toda a lógica de negócio (depósito, saque, transferência, idempotência, saldo insuficiente) sem precisar de Postgres nem Spring.
- **`wallet-bootstrap/.../ArchitectureTest`** — usa a biblioteca ArchUnit para verificar, automaticamente, as regras da seção 1 (domínio não depende de framework, aplicação não depende de adapter, `adapter.in` não depende de `adapter.out`). Roda como parte normal de `mvn verify`.
- **`wallet-bootstrap/.../WalletCoreConcurrencyTest`** — o único que precisa de Docker (sobe um PostgreSQL descartável via Testcontainers). Testa coisas que só fazem sentido contra um banco de verdade: 100 depósitos + 100 saques simultâneos na mesma carteira, saques concorrentes que não podem estourar o saldo, transferências A↔B simultâneas sem deadlock, a mesma `Idempotency-Key` disparada em paralelo, isolamento entre tenants via RLS, e a imutabilidade do ledger (tenta `UPDATE`/`DELETE` direto e espera que falhe).

Para rodar tudo: `mvn verify` na raiz de `wallet-core/` (precisa de Docker rodando, por causa do último item acima).

## 13. Erros comuns e onde procurar

| Sintoma | Onde olhar primeiro |
|---|---|
| `403` numa rota nova que você criou | `SecurityConfig.apiChain(...)` — provavelmente falta a linha da rota |
| `INSUFFICIENT_FUNDS` inesperado | Confira se a conta é `CUSTOMER` (nunca fica negativa) vs `SETTLEMENT` (pode); `Account.Kind` |
| Saldo "sumindo" ou duplicando em teste de carga | `JdbcAccountRepository.applyDelta` — confira se o `WHERE` da query não foi alterado |
| `IDEMPOTENCY_KEY_REUSED` (409) inesperado | O `fingerprint` mudou entre duas chamadas com a mesma chave — confira `MoveMoneyService.fingerprint(...)` e os parâmetros que entram nele |
| Auditoria (`/audit`) reportando inconsistência | Veja `docs/adr/007-observabilidade.md`; comece pelo `AuditLedgerService`, confira se algum código está gravando `LedgerEntry` fora do fluxo normal de `MoveMoneyService` |
| Teste `ArchitectureTest` falhando depois de uma mudança sua | Você importou algo de framework dentro de `wallet-domain` ou `wallet-application`, ou um adapter `in` passou a depender de um adapter `out` diretamente |
| `WalletCoreConcurrencyTest` não roda / é pulado | Falta Docker disponível — o teste usa `Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable())` e se pula sozinho sem Docker |
