# ADR-002 — Ledger append-only de partidas dobradas com replay de saldo

**Status:** aceita

## Contexto
"Toda transação precisa ter auditoria de partida" e "temos que ser capazes de recriar o saldo da carteira com todos os eventos".

## Decisão
1. Cada movimentação é uma `LedgerTransaction` **balanceada**: Σ débitos = Σ créditos. A invariante é validada no construtor do domínio e novamente pelo banco (trigger *deferred* no `COMMIT`).
2. Depósito = débito na conta de *settlement* do tenant + crédito na conta do cliente. Saque = o inverso. Transferência = débito na origem + crédito no destino.
3. As contas de settlement são **N shards por tenant** (a shard é escolhida pelo hash do id da transação) para evitar uma única linha quente.
4. `ledger_entry` é **append-only** (triggers e ausência de privilégio de `UPDATE/DELETE`). Cada entrada guarda `sequence_no` (sem buracos, por conta) e `balance_after`.
5. `account.balance_cents` é uma **projeção mantida na mesma transação** do lançamento (não é eventual). `account.version` é sempre igual ao `sequence_no` da última entrada.
6. `AuditLedger` faz o replay: confere ausência de buracos na sequência, o saldo corrente após cada entrada, e se saldo/versão finais batem com a projeção.

## Consequências
- (+) Saldo reconstruível e verificável a qualquer momento; a soma de todos os saldos do tenant (clientes + settlement) é sempre zero.
- (+) Qualquer adulteração (ou bug) é detectável por replay.
- (−) Toda movimentação escreve 2 entradas + atualiza 2 linhas de saldo.
- Próximo passo natural: *hash chain* por conta para evidência criptográfica de adulteração e job de reconciliação agendado.
