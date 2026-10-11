#!/usr/bin/env bash
# Roda a cada início do Codespace: sobe a stack e o console em segundo plano, sem segurar o terminal.
#   tail -f /tmp/dev-stack.log    o progresso da stack
#   tail -f /tmp/console.log      o ng serve do console
#
# "up --fast" constrói só as imagens que ainda não existem: na primeira vez, todas (15 a 25 minutos); nos inícios
# seguintes, nenhuma. Depois de mudar o código de um serviço, rode "scripts/dev-stack.sh up" para reconstruir.
cd "$(dirname "$0")/.."

# O Docker do Codespace (docker-in-docker) leva alguns segundos para aceitar comandos.
for _ in $(seq 1 60); do
  docker info >/dev/null 2>&1 && break
  sleep 2
done

setsid nohup bash -c '
  scripts/dev-stack.sh up --fast
  # O cache de build ocupa o disco do Codespace, que conta na cota gratuita; as imagens prontas ficam.
  docker builder prune -af >/dev/null
' >/tmp/dev-stack.log 2>&1 < /dev/null &

setsid nohup scripts/dev-stack.sh console >/tmp/console.log 2>&1 < /dev/null &

echo "Stack subindo em segundo plano: tail -f /tmp/dev-stack.log (console: tail -f /tmp/console.log)"
