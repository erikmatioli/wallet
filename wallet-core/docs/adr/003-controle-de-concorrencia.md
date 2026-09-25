# ADR-003 — Controle de concorrência do saldo

**Status:** aceita

## Contexto
Várias entradas e saídas simultâneas na mesma carteira, com retentativas de clientes e picos. Requisitos: sem lost update, sem saldo negativo, sem deadlock, sem lançamento duplicado.

## Decisão
- **Atualização condicional atômica**: `UPDATE account SET balance = balance + :d, version = version + 1 WHERE id = :id AND status = 'ACTIVE' AND (allow_negative OR balance + :d >= 0) RETURNING balance, version`. O PostgreSQL trava a linha; um escritor concorrente espera e **reavalia a condição** contra a versão recém-commitada (READ COMMITTED). A verificação de saldo e a escrita são uma única instrução.
- A `version` retornada vira o `sequence_no` do lançamento: sequência sem buracos por conta, garantida pelo lock da linha.
- **Ordem determinística de lock**: as pernas são processadas em ordem de `account_id`, então A→B e B→A nunca formam ciclo.
- **Idempotência primeiro**: o passo 1 da transação é `INSERT ... ON CONFLICT (tenant, idempotency_key) DO NOTHING`. Requisições paralelas com a mesma chave esperam a primeira; as demais recebem o resultado original. Uma fingerprint do pedido detecta reuso da chave com corpo diferente (409).
- **Tudo em uma transação** (READ COMMITTED, timeout 10 s, `lock_timeout` 3 s). Erros transitórios (deadlock, lock timeout, conexão) → retry com backoff exponencial + jitter; como a transação inteira é refeita e é idempotente, o retry é seguro. Se esgotar, HTTP 503 com orientação de repetir.
- Leituras consistentes (auditoria) usam `REPEATABLE READ` somente leitura.

## Alternativas
| Opção | Motivo da rejeição |
|---|---|
| `SELECT ... FOR UPDATE` + regra no Java | Mais round-trips e tempo de lock; a regra "saldo >= 0" ficaria fora do banco |
| Lock otimista (coluna `version` + retry) | Em conta quente gera tempestade de retries e desperdício de trabalho |
| `SERIALIZABLE` | Custo e taxa de aborts desnecessários, dado que a invariante é de linha única |
| Lock distribuído (Redis) | Nova dependência crítica sem ganho: o banco já serializa a linha |

## Consequências
- (+) Correção sem depender de disciplina do código; o `CHECK (balance >= 0)` do banco é a última barreira.
- (−) Contas extremamente quentes serializam na linha (ver ADR-001).
