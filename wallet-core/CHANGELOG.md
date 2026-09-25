# Changelog

O changelog completo e detalhado de cada versão é gerado automaticamente a cada release, a
partir dos pull requests mergeados (agrupados por label — ver `.github/release.yml`), e publicado
como a descrição de cada [GitHub Release](../../releases). Este arquivo não duplica esse conteúdo
manualmente, para não divergir dele.

Formato geral: [Keep a Changelog](https://keepachangelog.com/pt-BR/1.0.0/). Versionamento:
[SemVer](https://semver.org/lang/pt-BR/) — ver `CONTRIBUTING.md#versionamento`.

## [Unreleased]

- Observabilidade: métricas de negócio, tracing OpenTelemetry, auditoria contínua com alertas de
  inconsistência (ver `docs/adr/007-observabilidade.md`).
- Fluxo de controle de versão e pipeline de CI/CD (ver `CONTRIBUTING.md` e `docs/adr/008-versionamento-e-cicd.md`).
- inicio do código em 20/09/2026