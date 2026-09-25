# ADR-006 — Arquitetura hexagonal com módulos Maven

**Status:** aceita

## Decisão
- `wallet-domain`: Java puro (records, sealed types). Regras: `Money`, `LedgerTransaction`, validação de CPF/CNPJ, número de conta.
- `wallet-application`: casos de uso (portas de entrada) e portas de saída (repositórios, outbox, publisher, hasher, `TransactionRunner`). **Sem Spring** — os serviços são instanciados em `UseCaseConfig` (bootstrap).
- Adapters de entrada/saída dependem do `application`, jamais um do outro.
- `ArchitectureTest` (ArchUnit) torna as regras executáveis: domínio sem framework, aplicação sem adapters/framework, `adapter.in` ⟂ `adapter.out`.

## Consequências
- (+) Regras de negócio testáveis em milissegundos com fakes em memória (`MoveMoneyServiceTest`), sem container.
- (+) Trocar o publisher (Kafka), o banco de dados de um tenant ou o protocolo de entrada (gRPC) não toca o núcleo.
- (−) Mais arquivos e mapeamentos do que um CRUD em camadas; é o custo consciente para um core financeiro.
