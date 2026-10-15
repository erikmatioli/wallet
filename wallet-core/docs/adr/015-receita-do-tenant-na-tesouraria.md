# ADR-015 — A receita do tenant na tesouraria e a retirada dela

**Status:** aceita e implementada

## Contexto
A ADR-014 criou a conta de receitas do tenant (`TENANT_REVENUE`): as tarifas da cesta (wallet-fees) entram nela e
os estornos de tarifa saem dela. O core já mostra o saldo e o extrato (`GET /v1/treasury/revenue`), mas o console
não, e não há como o tenant tirar esse dinheiro da plataforma.

### O que o banco local mostrou (15/10/2026, script só de leitura)
| demo-tenant | Valor |
|---|---|
| Receita do tenant | R$ 6,50 (9 tarifas, R$ 8,50, menos 2 estornos, R$ 2,00) |
| Lastro esperado (soma das contas de lastro) | R$ 40,50 |
| Saldo dos clientes | R$ 34,00 |

- **A tela da tesouraria explica errado a posição.** Ela diz "soma do lastro = soma dos clientes"; a diferença de
  R$ 6,50 é a receita. A tarifa só troca o dono de um dinheiro que já está no lastro: lastro = clientes + receita do
  tenant + trânsito judicial.
- **A receita não tem um lugar próprio.** Ela está na Conta PI, no Caixa ou na Reservas, junto com o dinheiro dos
  clientes. Mandá-la para fora é tirar dinheiro de uma conta de lastro de verdade.

## Decisões

### 1. A receita na tela da tesouraria
Um cartão **Receitas do tenant** na `/tesouraria` (com `ledger:audit`): o saldo disponível, o **faturado no mês**
(tarifas menos estornos) e o **retirado no mês**, e o extrato da conta, com cada tarifa, cada estorno de tarifa e
cada retirada (origem, destino, comprovante e operador). A explicação da posição passa a ser "lastro = clientes +
receitas do tenant + trânsito judicial".

O faturado no mês não varre o ledger: é o saldo agora, menos o saldo no fim do mês anterior (a busca da ADR-012),
mais o retirado no mês (poucas linhas, da tabela de detalhe). No primeiro minuto do mês, enquanto o mês anterior
ainda pode receber lançamentos, ele não é mostrado.

### 2. A retirada da receita
Um tipo de transação novo, **`REVENUE_WITHDRAWAL`** ("Retirada de receita"), em
`POST /v1/treasury/revenue/withdrawals`, com `treasury:write` e `Idempotency-Key`:

| Débito | Crédito |
|---|---|
| conta de receitas do tenant | a conta de lastro de onde o dinheiro saiu (uma shard dela) |

- O lastro esperado da origem cai: o dinheiro saiu da plataforma. Os clientes não mudam, e a soma de todas as
  contas continua zero.
- **A origem é uma conta de lastro de verdade**, escolhida pela tesouraria; nunca **A regularizar**
  (`INVALID_TREASURY_SOURCE`), que é dinheiro criado e não um lugar.
- **A origem pode ficar negativa**, como na TED de depósito judicial: o cartão dela mostra o alerta de sempre, e a
  tesouraria recompõe.
- **No máximo o saldo da receita:** a conta de receitas não fica negativa (`INSUFFICIENT_FUNDS`).
- O pedido leva o **destino** (banco, agência e conta do tenant), o **comprovante** (obrigatório), uma observação e o
  operador. Ficam em `revenue_withdrawal_detail`, só de inserção, como os outros detalhes. O extrato da conta de
  lastro mostra o comprovante e a observação, como numa movimentação de tesouraria.

```json
{ "backingAccountId": "<Reservas>", "amount": 5.00,
  "destination": { "bank": "Itaú (341)", "branch": "0001", "account": "12345-6" },
  "document": "TED 000123", "note": "receita de outubro", "operator": "Ana Tesouraria" }
```

### 3. O que não muda
O wallet-fees ignora o tipo novo (não é uma operação tarifada). Os clientes, o app e o M-Wall nunca o veem: ele só
toca contas internas. O evento `wallet.transaction.posted.v1` é publicado como o de qualquer transação.

## Fora do escopo
- Cadastro das contas de destino do tenant (hoje o destino vai em cada retirada).
- Mandar a TED de verdade: como na cobertura e na TED judicial, o core registra o que a tesouraria fez lá fora.
- Contabilização fiscal da receita.

## Consequências
- **Positivas:** a tesouraria acompanha o faturamento e o tira da plataforma com partidas dobradas, comprovante e
  histórico imutável; a posição volta a fechar na tela.
- **Negativas:** mais um tipo de transação e uma tabela de detalhe.
