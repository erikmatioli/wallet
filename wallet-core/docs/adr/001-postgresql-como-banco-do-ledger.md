# ADR-001 — PostgreSQL como banco do ledger e do estado de saldo

**Status:** aceita

## Contexto
O core precisa de alto volume de transações, consistência forte no saldo (nunca negativo, sem lost update), auditoria imutável e reconstrução do saldo a partir de eventos. O acesso é predominantemente por chave (conta) e o padrão de escrita é *append* no ledger + atualização atômica de uma linha de saldo.

## Decisão
Usar **PostgreSQL** como fonte única de verdade (ledger + projeção de saldo na mesma transação).

## Alternativas consideradas
| Opção | Por que não (agora) |
|---|---|
| **TigerBeetle** (ledger especializado) | Throughput excelente em contas quentes, mas é jovem, não é SQL, exige operação e integração dedicadas e cria acoplamento forte. Fica como caminho de evolução se uma única conta passar a exigir mais do que o Postgres entrega |
| **Cassandra / DynamoDB** | Sem transações multi-linha com invariantes fortes; saldo com invariantes (`>= 0`) fica complexo e caro |
| **Kafka / EventStoreDB como fonte de verdade** | Consistência do saldo passaria a ser eventual; validar "saldo suficiente" exigiria uma segunda fonte |
| **MySQL** | Viável, mas sem RLS nativo, sem `RETURNING`, constraints parciais e triggers *deferred* que usamos como defesa em profundidade |

## Consequências
- (+) ACID, lock por linha, `UPDATE ... RETURNING`, índices únicos parciais, RLS, particionamento declarativo, ecossistema gerenciado (RDS/Aurora/Cloud SQL).
- (−) O throughput de **uma mesma conta** é limitado pela serialização da linha. Mitigação já aplicada nas contas internas (shards de settlement); contas de clientes muito quentes devem ser tratadas caso a caso.
- Escala horizontal: réplicas de leitura para extratos, particionamento do ledger por tempo, e sharding por tenant (Citus ou banco por tenant) quando necessário.
