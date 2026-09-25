# Contribuindo com o Wallet Core

Este documento é o fluxo de controle de versão do projeto: como branches, commits, pull requests e releases se encaixam. As decisões por trás dele (e as alternativas descartadas) estão em [`docs/adr/008-versionamento-e-cicd.md`](docs/adr/008-versionamento-e-cicd.md).

## 1. Modelo de branches: trunk-based

- **`main` é sempre "deployável"**: todo commit nela já passou por `mvn verify` completo (unitários, ArchUnit, testes de concorrência) e por build do Docker image.
- Trabalho novo vive em branches curtas, criadas a partir de `main`:
  - `feature/<slug>` — nova funcionalidade
  - `fix/<slug>` — correção de bug
  - `chore/<slug>` — manutenção (dependências, CI, refactor sem mudança de comportamento)
- Não há branch `develop` nem `release/*` de uso corrente — só existiriam como exceção pontual, se um dia for preciso manter um hotfix numa versão major antiga em paralelo ao desenvolvimento de uma nova (nesse caso, a branch nasce a partir da **tag** da versão a corrigir, não de `main`).
- Branches de trabalho são de vida curta: abra o PR assim que houver algo revisável, não acumule semanas de mudança numa branch só.

## 2. Commits e títulos de PR: Conventional Commits

Como o merge para `main` é sempre **squash merge**, o título do PR vira a mensagem de commit em `main` — é ele que precisa seguir o padrão, os commits individuais dentro do PR podem ser bagunçados à vontade:

```
<tipo>(<escopo opcional>): <descrição no imperativo>
```

Tipos usados neste projeto: `feat`, `fix`, `docs`, `refactor`, `perf`, `test`, `build`, `ci`, `chore`, `revert`. Para uma mudança que quebra compatibilidade (ex.: muda o contrato de uma API pública), acrescente `!` depois do tipo (`feat!: ...`) ou um rodapé `BREAKING CHANGE: ...` no corpo do PR.

Exemplos:
```
feat(ledger): add reversal transaction type
fix(auth): reject expired JWT with 401 instead of 500
docs(readme): document the observability stack
ci: add Trivy scan to the docker job
```

O workflow `ci.yml` valida automaticamente que o título do PR segue esse formato (job `pr-title`).

## 3. Fluxo de Pull Request

1. Abra a branch a partir de `main` atualizada.
2. Rode `mvn verify` localmente antes de abrir o PR (precisa de Docker, pela mesma razão que o CI precisa — ver README).
3. Abra o PR contra `main`. O template já traz o checklist.
4. Adicione um **label** (`feature`, `fix`, `docs`, `dependencies`, `breaking-change`, `observability`, ...) — é o que alimenta as release notes automáticas (`.github/release.yml`).
5. CI roda sozinho: `build-test` (verify completo), `pr-title` (título conventional), `docker` (build da imagem + scan de vulnerabilidades via Trivy, não bloqueante).
6. `CODEOWNERS` solicita revisão automaticamente para os caminhos sensíveis (núcleo financeiro, migrations, pipeline). Ajuste os donos reais em `.github/CODEOWNERS` antes de depender disso.
7. Squash-merge após aprovação e CI verde. Apague a branch.

### Configuração de proteção de branch (feita uma vez, na UI do GitHub/GitLab)

Este repositório não pode configurar isso sozinho — são ajustes manuais em Settings › Branches:
- Exigir PR antes de mergear em `main` (sem push direto).
- Exigir que os checks `build-test` e `pr-title` estejam verdes.
- Exigir squash merge (desabilitar merge commit e rebase merge, para manter um commit por PR em `main`).
- Exigir revisão de Code Owners.
- Em Settings › Actions › General, marcar "Read and write permissions" para o `GITHUB_TOKEN` — é o que permite `ci.yml`/`release.yml` publicarem imagens no GHCR sem precisar cadastrar um secret extra.

## 4. Versionamento

O projeto segue [SemVer](https://semver.org/lang/pt-BR/) (`MAJOR.MINOR.PATCH`), mas **a decisão de qual número usar é manual** — não há ferramenta automática de bump. Ao preparar uma release, olhe os tipos de commit acumulados desde a última tag:

- Só `fix`/`docs`/`chore`/`refactor` sem mudança de contrato → **PATCH**
- Algum `feat` → **MINOR**
- Algum `!` (breaking change) → **MAJOR**

O `pom.xml` em `main` permanece com uma versão `-SNAPSHOT` fixa (não é incrementado a cada release) — ela é só um marcador de build local. **A versão real de cada artefato publicado vem sempre da tag git**, definida na hora do build de release (`mvn versions:set` roda dentro do workflow, sem commitar de volta em `main`). Ver ADR-008 para o porquê dessa escolha em vez do fluxo clássico do `maven-release-plugin`.

## 5. Processo de release

```bash
git checkout main
git pull
git tag wallet-core-v0.2.0          # escolha o número seguindo a seção 4
git push origin wallet-core-v0.2.0
```

O prefixo `wallet-core-` existe porque este é um repositório monorepo (`wallet/`): quando um segundo projeto entrar no mesmo repo, ele terá seu próprio prefixo de tag (ex.: `outro-projeto-v1.0.0`) e seu próprio workflow de release, sem ambiguidade sobre qual projeto uma tag `v1.2.3` isolada estaria se referindo.

Isso dispara `.github/workflows/release.yml`, que sozinho:
1. Roda `mvn verify` de novo (rede de segurança — nunca confie apenas no CI que rodou dias atrás no PR).
2. Define a versão Maven a partir da tag e empacota o jar.
3. Builda e publica a imagem Docker no GHCR com três tags: `vX.Y.Z` completo, `X.Y` (major.minor) e `latest`, sob `ghcr.io/erikmatioli/wallet/wallet-core`.
4. Cria uma GitHub Release com notas geradas automaticamente (agrupadas pelos labels dos PRs, ver `.github/release.yml`) e o jar anexado.

Não existe passo manual de "aprovar a release" — a tag *é* a aprovação. Se algo der errado depois de já ter dado tag, a correção é uma nova tag de patch, nunca mover ou apagar uma tag já publicada.

### Hotfix numa versão antiga

Se `main` já tem mudanças incompatíveis com a versão que precisa do hotfix:
```bash
git checkout -b fix/algo-critico wallet-core-v1.4.2   # nasce da tag, não de main
# ... corrige, PR, merge nessa branch (não em main) ...
git tag wallet-core-v1.4.3
git push origin wallet-core-v1.4.3
```
Depois, considere se a correção também precisa ser portada para `main` via um PR normal.

## 6. Imagens Docker publicadas

| Tag | Quando é publicada | Uso pretendido |
|---|---|---|
| `ghcr.io/erikmatioli/wallet/wallet-core:edge` | A cada push em `main` | Testar o que está em `main` agora, antes da próxima release |
| `ghcr.io/erikmatioli/wallet/wallet-core:sha-<commit>` | A cada push em `main` | Referenciar um commit exato (rollback preciso, debugging) |
| `ghcr.io/erikmatioli/wallet/wallet-core:vX.Y.Z` | A cada tag de release | Deploy em produção — sempre uma versão exata, nunca `latest` |
| `ghcr.io/erikmatioli/wallet/wallet-core:X.Y` | A cada tag de release | Aponta para o patch mais recente daquela minor, se você quiser receber patches automaticamente |
| `ghcr.io/erikmatioli/wallet/wallet-core:latest` | A cada tag de release | Conveniência para testar localmente; não use em produção |

## 7. O que ainda não está automatizado (próximos passos)

- **Deploy contínuo**: `release.yml` publica a imagem, mas não faz deploy em nenhum ambiente — isso depende de onde a aplicação vai rodar (Kubernetes, ECS, etc.), que ainda não foi definido. O ponto de extensão é um job novo em `release.yml` (ou um workflow separado disparado por `workflow_run`) que puxe a imagem recém-publicada e a implante.
- **Cobertura de testes (JaCoCo)**: não configurada ainda; ver ADR-008 para o porquê de ter ficado de fora desta rodada.
- **Verificação de licenças de dependências**: não configurada.
