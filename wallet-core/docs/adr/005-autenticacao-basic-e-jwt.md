# ADR-005 — Autenticação: Basic para obter token, JWT para chamar as APIs

**Status:** aceita

## Decisão
- `POST /v1/auth/token` autentica o **client credentials** do tenant via HTTP Basic (`client_id:client_secret`) e devolve um **JWT RS256** de 10 minutos com `tenant_id`, `scope`, `iss`, `sub`, `jti`, `exp`.
- Todas as demais rotas exigem **Bearer JWT** e verificam escopos (`customers:write`, `accounts:read`, `ledger:write`, `ledger:audit`). Rotas não mapeadas são negadas (`denyAll`).
- Segredos são guardados apenas como hash adaptativo (bcrypt via `DelegatingPasswordEncoder`, permite migrar o algoritmo). O `DaoAuthenticationProvider` mitiga enumeração de clientes por tempo de resposta.
- Chaves assimétricas: consumidores validam via `/.well-known/jwks.json`. O `kid` permite rotação. Sem chaves configuradas, sobe um par efêmero e registra um aviso (somente dev).
- Sessão stateless, CSRF desligado (API sem cookies).

## Consequências e próximos passos
- Use TLS sempre (Basic só é seguro sobre TLS). mTLS opcional pode ser adicionado como segunda camada por tenant.
- Chaves em KMS/Secrets Manager e rotação automática; *rate limiting* e bloqueio progressivo no endpoint de token.
- Sem *refresh token* por desenho: clientes máquina-a-máquina simplesmente pedem outro token.
