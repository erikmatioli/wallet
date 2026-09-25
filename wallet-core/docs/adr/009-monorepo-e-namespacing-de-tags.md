# ADR-009 — Reestruturação para monorepo (`wallet/wallet-core`)

**Status:** aceita — refina ADR-008, não o substitui

## Contexto
O ADR-008 assumiu, implicitamente, que o repositório continha um único projeto (`wallet-core`
era a própria raiz do repositório). Ao configurar o repositório real no GitHub
(`github.com/erikmatioli/wallet`), a decisão foi organizá-lo como um **monorepo**: a raiz do
repositório chama-se `wallet/`, e `wallet-core` passa a ser uma subpasta dela, com espaço
reservado para outros projetos entrarem no mesmo repositório no futuro.

Essa mudança de estrutura de pastas quebrou uma suposição que o ADR-008 não tinha declarado
explicitamente: que os workflows do GitHub Actions viviam na raiz do checkout junto com o
`pom.xml`. O sintoma foi concreto — a aba *Actions* do GitHub mostrava a tela inicial de "Get
started with GitHub Actions" em vez dos workflows, porque `.github/workflows/` só é reconhecido
pelo GitHub quando está na **raiz do repositório**, e ali ele estava um nível abaixo
(`wallet/wallet-core/.github`).

## Decisões

### 1. `.github/` sobe para a raiz verdadeira do repositório
É a única pasta que **precisa** estar na raiz para o GitHub Actions funcionar. Todo o resto do
projeto Java (`pom.xml`, módulos, `Dockerfile`, `docker-compose.yml`, `docs/`, `README.md`,
`CONTRIBUTING.md`) permanece dentro de `wallet-core/`, contido e autossuficiente — continua dando
para clonar só o histórico dessa pasta (`git subtree`/`sparse-checkout`) se um dia isso for
necessário.

### 2. O workflow reutilizável ganha um parâmetro `project-dir`
`build-test.yml` (chamado tanto por `ci.yml` quanto por `release.yml`) recebe
`inputs.project-dir` (default `wallet-core`), e usa `defaults.run.working-directory` para rodar
`mvn verify` dentro da subpasta certa. Quando um segundo projeto entrar no repositório, ele ganha
seu **próprio** `ci.yml`/`release.yml` (nomeados, ex.: `ci-outro-projeto.yml`), que chama o mesmo
`build-test.yml` passando `project-dir: outro-projeto` — sem duplicar a lógica de build.

### 3. `ci.yml` ganha filtros de path
`paths: ["wallet-core/**", ".github/workflows/ci.yml", ".github/workflows/build-test.yml"]` no
gatilho de `pull_request`/`push`. Sem isso, qualquer mudança em qualquer lugar do monorepo
(inclusive num projeto futuro, sem relação nenhuma com `wallet-core`) disparformattedaria o CI do
`wallet-core` à toa — o oposto do que se espera de um monorepo bem configurado.

### 4. Tags de release ganham o prefixo `wallet-core-`
Antes: `vX.Y.Z`. Agora: `wallet-core-vX.Y.Z`, e `release.yml` só dispara para esse padrão
(`on.push.tags: ["wallet-core-v[0-9]+.[0-9]+.[0-9]+"]`). Numa release única por repositório, uma
tag `v1.2.3` sozinha não tem ambiguidade. Num monorepo, ela teria: a que projeto essa versão se
refere? O prefixo resolve isso e permite que cada projeto do repositório tenha seu próprio
histórico de versões, independente dos demais.

### 5. Nome da imagem Docker ganha um segmento a mais
De `ghcr.io/erikmatioli/wallet:vX.Y.Z` para `ghcr.io/erikmatioli/wallet/wallet-core:vX.Y.Z`. O
GHCR suporta caminhos aninhados dentro do namespace do repositório; isso deixa claro, só pelo
nome da imagem, qual projeto do monorepo ela representa — necessário pelo mesmo motivo do item 4.

### 6. `CODEOWNERS` passa a ter caminhos prefixados por `wallet-core/`
Já que ele vive na raiz verdadeira do repositório agora, os caminhos que antes eram
`/wallet-domain/`, `/Dockerfile` etc. precisam declarar o prefixo `/wallet-core/...` para
continuarem apontando para os arquivos certos.

## Alternativas consideradas
| Opção | Por que não |
|---|---|
| Manter `wallet-core` como raiz do repositório (não usar monorepo) | Era a estrutura original do ADR-008 e a mais simples — descartada porque o usuário já decidiu, de propósito, reservar o repositório para múltiplos projetos futuros |
| Usar Git submodules em vez de monorepo | Adiciona complexidade operacional (clone recursivo, sincronização de submódulo) sem necessidade real hoje — um monorepo simples com path filters já resolve o isolamento entre projetos que se precisa agora |
| Tags sem prefixo, distinguindo por branch em vez de nome de tag | Complicaria o fluxo trunk-based (ADR-008, decisão 1), que depende de uma única `main` |

## Consequências
- (+) Aba *Actions* do GitHub volta a funcionar normalmente, mostrando os workflows.
- (+) Adicionar um segundo projeto ao repositório não exige tocar no pipeline do `wallet-core` — só criar os arquivos equivalentes para o novo projeto, reaproveitando `build-test.yml`.
- (−) Toda referência a tag/imagem Docker no `README.md`/`CONTRIBUTING.md` precisou ser atualizada para incluir o prefixo/segmento `wallet-core` — um lembrete de que, ao adicionar o próximo projeto, a documentação dele também precisa declarar seu próprio prefixo desde o início, para não repetir esse retrabalho.
