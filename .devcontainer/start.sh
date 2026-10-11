#!/usr/bin/env bash
# Roda ao abrir o Codespace (postAttachCommand), cada modo no seu terminal do VS Code:
#   bash .devcontainer/start.sh          sobe a stack e mostra o status no fim
#   bash .devcontainer/start.sh console  roda o console (ng serve) e fica rodando
#
# "up --fast" constrói só as imagens que ainda não existem: na primeira vez, todas (15 a 25 minutos); nas aberturas
# seguintes, nenhuma, e a stack sobe em poucos minutos. Depois de mudar o código de um serviço, rode
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

scripts/dev-stack.sh up --fast
# O cache de build ocupa o disco do Codespace, que conta na cota gratuita; as imagens prontas ficam.
docker builder prune -af >/dev/null
echo
echo "Stack no ar. O console abre pela aba PORTS, linha 'Console (4200)'."
