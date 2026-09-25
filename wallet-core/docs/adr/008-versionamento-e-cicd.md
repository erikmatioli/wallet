# ADR-008 — Controle de versão e CI/CD

**Status:** aceita

## Contexto
O projeto tem uma segunda versão (observabilidade) e precisa de um fluxo formal de controle de
versão e de um pipeline de CI/CD, para que crescer o time e lançar releases não dependa de
combinar manualmente "quem builda o quê e quando" a cada vez.

Assumção explícita: as decisões abaixo pressupõem **GitHub** (Actions, GHCR, Releases). Os
princípios (trunk-based, Conventional Commits, release por tag) valem para qualquer plataforma;
só a sintaxe dos workflows em `.github/workflows/` é específica do GitHub. Migrar para GitLab CI
ou Azure DevOps exigiria reescrever os arquivos YAML, não o fluxo em si.

## Decisões

### 1. Trunk-based development, não Git Flow
Uma única branch de longa duração (`main`), branches de feature/fix de vida curta, sem `develop`.
Combina bem com o pipeline: cada PR já builda a imagem Docker e roda a suíte completa (incluindo
os testes de concorrência via Testcontainers), então `main` está sempre num estado testado e
publicável. `Git Flow` (com `develop`, `release/*` de longa duração) resolveria um problema que
este projeto não tem hoje — múltiplas versões em desenvolvimento paralelo — e adicionaria
cerimônia de merge desnecessária.

### 2. Conventional Commits nos títulos de PR, aplicados via squash merge
Squash merge garante um commit por PR em `main`; validar o **título do PR** (não cada commit
individual dentro dele) é suficiente e menos hostil ao fluxo de trabalho de quem está
desenvolvendo. Isso alimenta duas coisas: a decisão manual de próximo número de versão (seção 4
do CONTRIBUTING) e, indiretamente, a legibilidade do histórico de `main`.

### 3. Release disparada por tag git, versão derivada da tag — não `maven-release-plugin`
O `maven-release-plugin` clássico faz commit de volta em `main` (bump de versão + bump para o
próximo `-SNAPSHOT`), o que exige permissão de push direto em branch protegida e cria commits só
de "versionamento" no histórico. Em vez disso:
- `pom.xml` em `main` mantém uma versão `-SNAPSHOT` fixa, nunca incrementada por commit.
- `release.yml` roda `mvn versions:set -DnewVersion=<tag sem o v>` **dentro do job**, sem
  `versions:commit` seguido de push — a mudança de versão vive só naquele checkout efêmero.
- A tag git é a única fonte de verdade de "qual é a versão"; o artefato (jar, imagem Docker)
  carrega essa versão só porque o build foi feito depois desse `versions:set`.

Trade-off aceito: não dá para saber "que versão é essa" só olhando o `pom.xml` de `main` fora de
um build de release — é preciso olhar as tags. Isso é intencional: o `pom.xml` de `main` não
representa uma versão publicada, representa "o próximo trabalho em andamento".

### 4. GitHub Actions com workflow reutilizável (`build-test.yml`)
`ci.yml` (todo PR/push) e `release.yml` (toda tag) chamam o mesmo `workflow_call` para
`mvn verify`. Garante que uma release nunca pula uma verificação que o CI normal already faz —
sem duplicar o YAML dos steps em dois arquivos que puderiam divergir silenciosamente.

### 5. GHCR (`ghcr.io`) em vez de Docker Hub
Zero configuração de secrets: `GITHUB_TOKEN` já autentica no GHCR do mesmo repositório (só exige
habilitar "Read and write permissions" nas configurações do repositório, um passo manual único,
documentado no CONTRIBUTING). Nenhum outro registry usado nesta entrega evita essa fricção de
cadastrar credenciais de terceiros.

### 6. Notas de release nativas do GitHub, não uma ferramenta externa (ex.: `release-please`)
`generate_release_notes: true` + `.github/release.yml` (arquivo de configuração nativo do GitHub)
agrupam PRs mergeados por label, sem Action de terceiros, sem token adicional, sem outro arquivo
de configuração para manter sincronizado com o formato dos commits. O custo é que alguém precisa
lembrar de rotular os PRs (documentado no checklist do template de PR) — considerado um trade-off
aceitável frente à complexidade de rodar `release-please` (que gerencia sua própria "release PR"
e reintroduziria exatamente o padrão de commit-back que a decisão 3 evitou).

### 7. Trivy no lugar de OWASP Dependency-Check
Ambos escaneiam vulnerabilidades conhecidas, mas o Dependency-Check precisa de uma chave de API
do NVD para não sofrer rate-limit em CI (mais um secret para gerenciar, mais uma fonte de
flakiness). O Trivy escaneia a **imagem Docker já construída** (SO + dependências Java juntos,
sem chave de API) e publica o resultado na aba *Security* do GitHub via SARIF — cobre mais
superfície (a imagem base também) com menos fricção operacional. Roda como relatório, não
bloqueante (`exit-code: "0"`), para não travar todo PR por uma CVE de severidade que ainda precisa
ser triada por um humano.

### 8. Fora desta rodada: cobertura de testes (JaCoCo) e deploy contínuo
- **JaCoCo**: agregar cobertura num reactor multi-módulo exige um módulo agregador dedicado ou o
  goal `report-aggregate`, configuração não trivial o suficiente para justificar ficar de fora
  desta entrega e entrar como item futuro (ver CONTRIBUTING.md, seção 7), em vez de meio-implementada.
- **Deploy contínuo**: o pipeline publica a imagem versionada no GHCR, mas não a implanta em
  nenhum ambiente — isso depende de uma decisão de infraestrutura (Kubernetes? ECS?) que ainda
  não foi tomada. Adicionar esse passo é uma extensão direta de `release.yml` quando o alvo for
  definido.

## Consequências
- (+) Nenhum secret além do `GITHUB_TOKEN` embutido é necessário para o pipeline completo funcionar.
- (+) Um PR nunca chega em `main` sem já ter tido sua imagem Docker construída com sucesso —
  captura exatamente o tipo de falha (dependência faltando) encontrado manualmente durante o
  desenvolvimento deste projeto, antes de chegar em produção.
- (+) Cortar uma release é um único comando (`git tag && git push`), sem passo manual de aprovação
  ou de edição de arquivo de versão.
- (−) Decidir o próximo número SemVer continua manual — se o time crescer a ponto de isso virar
  gargalo, `release-please` ou similar volta a ser uma opção a reconsiderar (ver decisão 6).
- (−) Hotfix em versão antiga é um fluxo à parte (branch a partir da tag), não o caminho principal
  — documentado no CONTRIBUTING como exceção, não como caso comum.
