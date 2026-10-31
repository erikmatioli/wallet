#!/usr/bin/env bash
# Roda ao abrir o Codespace (postAttachCommand), cada modo no seu terminal do VS Code:
#   bash .devcontainer/start.sh          sobe a stack e mostra o status no fim
#   bash .devcontainer/start.sh console  roda o console (ng serve) e fica rodando
#
# Antes de subir, traz o que chegou no GitHub para a branch atual (git pull --ff-only), mas só quando não há
# alteração local nos arquivos versionados: trabalho em andamento nunca é mexido. Os serviços que o pull alterou
# têm a imagem reconstruída; os demais sobem com a imagem que já existe.
#
# "up --fast" constrói só as imagens que ainda não existem: na primeira vez, todas (15 a 25 minutos); nas aberturas
# seguintes, só as que o pull alterou, e a stack sobe em poucos minutos. Depois de mudar o código de um serviço, rode
# "scripts/dev-stack.sh up" para reconstruir. Reabrir o Codespace com tudo no ar só confere o status.
cd "$(dirname "$0")/.."

if [[ "${1:-}" == "console" ]]; then
  if curl -s -o /dev/null http://localhost:4200/; then
    echo "O console já está rodando na porta 4200 (aba PORTS)."
    exit 0
  fi
  exec scripts/dev-stack.sh console
fi

# O Docker do Codespace (docker-in-docker) leva alguns segundos para aceitar comandos.
for _ in $(seq 1 60); do
  docker info >/dev/null 2>&1 && break
  sleep 2
done

# Serviços da stack, cada um com o seu docker-compose.yml (a mesma lista do scripts/dev-stack.sh).
SERVICES=(wallet-core wallet-pix wallet-scheduler wallet-otp wallet-fees wallet-app wallet-mobile)

branch=$(git rev-parse --abbrev-ref HEAD)
before=$(git rev-parse HEAD)
if ! git rev-parse --abbrev-ref '@{u}' >/dev/null 2>&1; then
  echo "==> git: a branch $branch não tem remota; sem pull."
elif [[ -n "$(git status --porcelain --untracked-files=no)" ]]; then
  echo "==> git: há alterações locais em $branch; sem pull (rode 'git pull' quando quiser)."
elif ! git pull --ff-only; then
  echo "==> git: o pull de $branch falhou (sem rede ou a branch divergiu do GitHub); a stack sobe como está."
fi

after=$(git rev-parse HEAD)
if [[ "$after" != "$before" ]]; then
  changed=$(git diff --name-only "$before" "$after")
  echo "==> git: $branch atualizada ($(git rev-list --count "$before..$after") commits novos)."
  for service in "${SERVICES[@]}"; do
    if grep -q "^$service/" <<<"$changed"; then
      echo "==> reconstruindo $service"
      (cd "$service" && docker compose build)
    fi
  done
  if grep -q "^wallet-console/package-lock.json$" <<<"$changed"; then
    echo "==> aviso: as dependências do console mudaram; rode 'cd wallet-console && npm ci' e reinicie o terminal 'console'."
  fi
  if grep -q "^.devcontainer/devcontainer.json$" <<<"$changed"; then
    echo "==> aviso: o devcontainer.json mudou; vale um 'Codespaces: Rebuild Container' (faça o backup antes, docs/codespaces.md)."
  fi
fi

scripts/dev-stack.sh up --fast
# O cache de build ocupa o disco do Codespace, que conta na cota gratuita; as imagens prontas ficam.
docker builder prune -af >/dev/null
echo
echo "Stack no ar. O console abre pela aba PORTS, linha 'Console (4200)'."
