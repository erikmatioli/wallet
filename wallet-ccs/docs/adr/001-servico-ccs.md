# ADR-001 (wallet-ccs) — Relacionamentos e arquivos do CCS

**Status:** proposta

## Contexto
O **CCS** (Cadastro de Clientes do Sistema Financeiro Nacional) é mantido pelo Banco Central. Toda
instituição informa **quem são os seus clientes e desde quando**, e o Judiciário e outros órgãos consultam o
CCS para descobrir onde uma pessoa tem conta. São duas obrigações:

1. **Atualização diária (arquivo ACCS001).** Uma linha por **pessoa** (CPF ou CNPJ), não por conta, com a
   data de início e, quando houver, a de fim do **relacionamento** com a instituição. É enviado entre as
   **20h do dia útil anterior e as 8h** do dia do movimento.
2. **Detalhamento (mensagem CCS0001/CCS0002, ou o arquivo ACCS005 quando a resposta é grande).** Quando um
   órgão consulta uma pessoa, a instituição responde **até as 8h do dia útil seguinte** com cada conta (os
   "bens, direitos e valores"), o vínculo da pessoa com ela (titular, representante, procurador) e as datas.

As regras do ACCS001 que mais pesam no desenho (leiaute v1.2.5):

| Operação (`TpOpCCS`) | Quando |
|---|---|
| `I` inclusão | começou um relacionamento; se ele já terminou antes de ser informado, vai com `DtIni` e `DtFim` |
| `A` alteração | terminou um relacionamento: **só a data de fim** pode ser alterada |
| `E` exclusão | o registro enviado estava **errado** e deve ser desconsiderado (não é o fim do relacionamento) |

- Um erro no CPF/CNPJ ou na data de início se corrige com uma **exclusão seguida de uma inclusão**, nessa ordem.
- O número da remessa (`NumRemessaArq`) é `AAAAMMDD` mais um sequencial de 4 dígitos, que **só avança
  depois que a remessa anterior foi aceita**.
- O Banco Central responde com o **ACCS002** (arquivo aceito ou rejeitado, com o erro) e o **ACCS003** (o
  resultado de cada registro). O **ACCS004** traz a posição completa do cadastro, para conciliar.
- No CCS a instituição é identificada pelo **CNPJ base** (8 dígitos), que é o `ispb` do tenant.

O core passa a ter bloqueios, encerramento com histórico e um feed para este serviço (ADR-013 do wallet-core).

## Decisões

### 1. Um serviço próprio, que **gera** os arquivos e não os transmite
O `wallet-ccs` é outro projeto do monorepo (ADR-009 do wallet-core): outro deploy, outro banco (`ccs`), CI
próprio com filtro de pasta e tags `wallet-ccs-vX.Y.Z`. É escrito em **Kotlin**, com o mesmo padrão do
wallet-scheduler: um módulo Maven, pacotes hexagonais verificados pelo ArchUnit, Spring Boot 4, `JdbcClient`,
Flyway, fakes em memória nos testes e Testcontainers.

O core não sabe nada de reporte regulatório: ele registra o que aconteceu com a conta, e o `wallet-ccs`
traduz isso em relacionamentos e arquivos.

**Não há transmissão ao Banco Central.** O envio de verdade exige o canal da RSFN, que não temos nem
simulado. O serviço gera e guarda cada arquivo; o operador baixa o XML no console, envia pelo canal da
instituição e **importa a resposta** (ACCS002 e ACCS003) de volta. O dia em que houver transmissão, ela entra
como mais um adaptador de saída, sem mudar o resto.

### 2. Lê o core por um feed ordenado
Para cada tenant configurado, um job chama `GET /v1/ccs/account-events?after={seq}` no core, com um token do
tenant com o escopo `ccs:read` (a mesma forma do agendador: credenciais por tenant e cache do token). Ele
guarda a última `seq` processada **na mesma transação** em que aplica os eventos: parado por dias, ele
retoma de onde parou e não perde nem repete nada.

O evento de domínio do outbox não serve aqui: ele não leva CPF nem nome, de propósito, e o publicador hoje só
grava log. O feed é uma leitura com escopo próprio, feita para quem tem obrigação de saber esses dados.

### 3. O modelo: conta, relacionamento, operação e remessa

| Tabela | O que é |
|---|---|
| `ccs_account` | o espelho de cada conta que importa ao CCS: titular (tipo e número do documento, nome), agência, conta, abertura e encerramento |
| `ccs_relationship` | o relacionamento de uma pessoa com o tenant: `dt_ini`, `dt_fim` (vazia enquanto aberto) e o que o Banco Central já sabe dele |
| `ccs_operation` | cada `I`, `A` ou `E` a informar, com a remessa em que foi (vazia enquanto pendente) e o resultado do ACCS003 |
| `ccs_remittance` | cada arquivo gerado: tenant, `NumRemessaArq`, `DtMovto`, quantidade, o XML, a situação e o erro |
| `ccs_feed_cursor` | por tenant, a última `seq` lida do core |

**Como um evento vira relacionamento** (por tenant e por CPF/CNPJ):

- **Conta aberta** e a pessoa não tem relacionamento aberto → abre um relacionamento com `dt_ini` = a data
  da abertura (horário de Brasília) e gera um `I`.
- **Conta aberta** e a pessoa já tem relacionamento aberto (outra conta) → nada a informar.
- **Conta encerrada** e era a última conta aberta da pessoa → fecha o relacionamento com `dt_fim` = a data do
  encerramento e gera um `A`.
- **Bloqueio e desbloqueio** não mudam o relacionamento: a pessoa continua cliente.
- Se o `I` ainda não foi enviado quando o relacionamento fecha, a operação pendente vira um só `I` com
  `DtIni` e `DtFim`, como o leiaute prevê.

Uma pessoa que encerra tudo e volta depois tem **dois** relacionamentos, um para cada período.

### 4. A remessa diária
Um job, em cada **dia útil** às **20h** de Brasília, gera por tenant o ACCS001 com **todas as operações
pendentes**, em ordem (a exclusão antes da inclusão que a corrige). O `DtMovto` é o próximo dia útil, e o
`NumRemessaArq` é a data de geração mais o sequencial do dia.

| Situação da remessa | Significa |
|---|---|
| `GENERATED` | gerada, esperando o operador enviar e importar a resposta |
| `ACCEPTED` | o ACCS002 disse aceito; as operações ficam como informadas |
| `REJECTED` | o ACCS002 disse rejeitado; as operações voltam a pendentes, com o erro |

- **Uma remessa por vez.** Enquanto uma está `GENERATED`, não se gera outra, porque o sequencial só avança
  depois do aceite. O console mostra o atraso, se houver.
- **Uma remessa rejeitada** não queima o número: a próxima geração usa o mesmo sequencial.
- **Um registro rejeitado no ACCS003**, com a remessa aceita, fica marcado com o erro na operação, para o
  operador ver e corrigir.
- **Sem operações pendentes**, não há arquivo no dia.

O XML segue o leiaute v1.2.5 (`CCSDOC`, `BCARQ` com o emissor = CNPJ base do tenant e o destinatário
`00038166`, o Banco Central; `SISARQ/CCSArqAtlzDiaria`) e é validado contra o XSD do Banco Central nos
testes. O qualificador é sempre `N` (normal): conta popular e conglomerado não existem aqui.

**Dias úteis:** o serviço tem o calendário de feriados nacionais (os fixos e os móveis, a partir da Páscoa:
Carnaval, Sexta-feira Santa e Corpus Christi). O agendador não precisou de um porque Pix e transferência
funcionam todos os dias; o CCS precisa.

### 5. Correções
Pelo console, o operador pode **excluir** um relacionamento informado por engano (gera um `E`) e, quando era
um erro de data de início, informar a data certa (gera o `E` e um `I` novo, nessa ordem). Toda correção pede
o motivo e o operador, como os bloqueios da conta.

### 6. O detalhamento
`POST /v1/ccs/details` recebe o que o pedido do Banco Central traz (o `NumCtrlCCSOr`, o CPF/CNPJ e o período)
e devolve a resposta montada a partir do `ccs_account`: cada conta da pessoa que existiu no período, com
agência, conta, tipo do bem, o vínculo (`TpVinc`), o nome e as datas de início e fim. A resposta fica guardada
com o pedido, e o XML (CCS0002, ou ACCS005 se for grande) é baixado pelo console, como a remessa.

Hoje o único vínculo é o **titular**: não existem representantes nem procuradores no core. Os códigos de tipo
de bem (`TpBDV`) e de vínculo (`TpVinc`) vêm do Dicionário de Domínios do SPB, com a versão anotada no código;
nenhum deles é inventado.

### 7. API e console
API com os JWT do wallet-core (JWKS), como o agendador. Escopos novos no core: **`ccs:read`** (consultar
relacionamentos, remessas e detalhamentos) e **`ccs:write`** (importar respostas, corrigir, detalhar).

Uma entrada nova no console, **`/ccs`**:

- **Remessas:** por dia, com a situação, a quantidade, o botão de baixar o XML e o de importar a resposta.
  Em destaque, a remessa esperando resposta e o atraso, se houver.
- **Pendentes:** as operações que vão na próxima remessa.
- **Relacionamentos:** a busca por CPF/CNPJ, com os períodos, as contas de cada um e o que já foi informado.
- **Detalhamento:** o formulário do pedido e as respostas já geradas.

## Fora do escopo
- **Transmissão pela RSFN** e o recebimento automático das respostas e dos pedidos de detalhamento.
- **Representantes legais e procuradores**, que o core não tem: quando existirem, entram como vínculos no
  detalhamento e como relacionamentos próprios no ACCS001.
- **Conciliação com o ACCS004** (a posição completa que o Banco Central guarda).
- **Ordem judicial com sigilo** (`SitInfDett` do detalhamento).

## Plano de entrega
1. **Core (ADR-013):** bloqueios, encerramento, `closed_at`, a regra do `applyDelta`, a transferência judicial e o feed
   `ccs/account-events`, com os escopos novos.
2. **Console:** bloqueios, transferência judicial, encerramento e linha do tempo na tela da conta.
3. **wallet-ccs, esqueleto:** projeto Kotlin, banco, CI, Docker Compose.
4. **wallet-ccs, relacionamentos:** leitura do feed e as regras do item 3.
5. **wallet-ccs, remessa:** a geração do ACCS001, o XSD, a importação do ACCS002 e do ACCS003, o calendário.
6. **Console, `/ccs`:** remessas, pendentes e relacionamentos.
7. **Detalhamento e correções.**

## Consequências
- **Positivas:** o core continua sem saber de reporte regulatório; os relacionamentos saem do histórico de
  status, sem varrer contas; o operador acompanha cada remessa, baixa e importa pelo console; a transmissão
  real entra depois como um adaptador.
- **Negativas:** mais um serviço e um banco para operar; o envio e a resposta dependem de um passo manual até
  existir a transmissão; o feed expõe CPF e nome a um cliente com `ccs:read`, que precisa ser restrito a ele.

## Referências
- [CCS — Leiautes de Arquivos e Mensagens, v1.2.5 (BCB)](https://www.bcb.gov.br/content/acessoinformacao/ccs_docs/CCS_Leiautes_Arquivos_Mensagens.pdf)
- [CCS — Orientação Técnica (BCB)](https://www.bcb.gov.br/content/acessoinformacao/ccs_docs/CCS%20Orienta%C3%A7%C3%A3o%20T%C3%A9cnica.pdf)
- [CCS — Manual do Usuário (BCB)](https://www.bcb.gov.br/content/acessoinformacao/Documents/ccs/ccs_manual.pdf)
- ADR-013 do wallet-core: bloqueios, encerramento e o feed de eventos.
