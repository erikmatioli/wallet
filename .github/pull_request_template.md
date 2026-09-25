## O que muda e por quê

<!-- Uma ou duas frases. Se resolve uma issue, referencie: Closes #123 -->

## Como testar

<!-- Passos manuais, se houver algo além de `mvn verify` -->

## Checklist

- [ ] O título do PR segue Conventional Commits (`feat: ...`, `fix: ...`, `chore: ...`, `docs: ...`, `refactor: ...`, `test: ...`, `ci: ...`) — é o que vira a mensagem de commit ao fazer squash-merge, e o que decide o próximo número de versão
- [ ] `mvn verify` passa localmente (unitários + ArchUnit + testes de concorrência com Testcontainers)
- [ ] Mudança em schema? Nova migration Flyway (nunca editei uma já existente)
- [ ] Mudança de comportamento relevante? Atualizei o README/ADR correspondente
- [ ] Adicionei um label ao PR (`feature`, `fix`, `docs`, `dependencies`, `breaking-change`, `observability`...) para as release notes automáticas
