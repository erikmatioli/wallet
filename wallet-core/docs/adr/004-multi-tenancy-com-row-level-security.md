# ADR-004 — Multi-tenancy com Row Level Security

**Status:** aceita

## Contexto
Produto white label: vários clientes (tenants) compartilham a plataforma e nunca podem ver dados uns dos outros.

## Decisão
- **Schema compartilhado** com `tenant_id` em todas as tabelas de negócio.
- **RLS do PostgreSQL** (`ENABLE` + `FORCE ROW LEVEL SECURITY`) com policy `tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid`.
- Cada transação começa com `set_config('app.tenant_id', :tenant, true)` (escopo local à transação). Sem essa configuração, nenhuma linha é visível.
- O tenant é extraído do **JWT assinado**, nunca de parâmetros do request. Os repositórios ainda filtram por `tenant_id` explicitamente (defesa em profundidade).
- A aplicação conecta com uma role **sem superuser e sem BYPASSRLS** (`wallet_app`); as migrations usam outra role (`wallet_owner`).

## Alternativas
| Opção | Observação |
|---|---|
| Schema por tenant | Isolamento maior, porém migrations e pool por tenant custam caro com muitos tenants |
| Banco por tenant | Máximo isolamento/compliance; alto custo operacional. Caminho para tenants que exigirem isolamento físico |

## Consequências
- (+) Um esquecimento de `WHERE tenant_id` no código não vaza dados.
- (−) `set_config` por transação (custo mínimo) e atenção: nunca usar pool de conexões com role superuser.
- Como o código depende só da porta `TransactionRunner`, migrar um tenant para schema/banco dedicado não muda o domínio nem os casos de uso.
