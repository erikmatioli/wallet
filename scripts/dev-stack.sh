#!/usr/bin/env bash
# Sobe, para e confere a stack inteira de desenvolvimento, na ordem das dependências: todos os composes entram na
# rede do wallet-core (wallet-core_default) e usam o Postgres dele, então o core vem primeiro.
#
#   scripts/dev-stack.sh up                   constrói o que mudou e sobe tudo, esperando cada serviço responder
#   scripts/dev-stack.sh up --fast            sobe sem reconstruir as imagens
#   scripts/dev-stack.sh up --segundo-tenant  sobe também o app-api (8084) e o M-Wall (8087) do segundo-tenant
#   scripts/dev-stack.sh down                 para tudo, na ordem inversa (os dados ficam: nunca apaga volumes)
#   scripts/dev-stack.sh status               containers e o health de cada serviço
#   scripts/dev-stack.sh console              roda o console (ng serve) aceitando conexões de fora da máquina
#
# O segundo-tenant é opcional (para testar o isolamento entre tenants), e o app desktop não faz parte da stack:
# roda só quando pedido, com "cd wallet-app && ./gradlew :app-desktop:run".
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

# projeto | portas que precisam responder antes do próximo subir | caminho do health
STACK=(
  "wallet-core|8080|/actuator/health"
  "wallet-pix|8081|/actuator/health"
  "wallet-scheduler|8082|/actuator/health"
  "wallet-otp|8085|/actuator/health"
  "wallet-fees|8088|/health"
  "wallet-app|8083|/actuator/health"
  "wallet-mobile|8086|/"
)

# As portas do segundo-tenant, que só sobe com --segundo-tenant (perfil "segundo-tenant" dos composes).
declare -A SECOND_PORTS=([wallet-app]=8084 [wallet-mobile]=8087)

wait_http() {
  local url=$1 tries=${2:-120}
  for ((i = 0; i < tries; i++)); do
    if curl -fsS -o /dev/null "$url" 2>/dev/null; then
      return 0
    fi
    sleep 3
  done
  return 1
}

up() {
  local build="--build" second=0
  for arg in "$@"; do
    case "$arg" in
      --fast) build="" ;;
      --segundo-tenant) second=1 ;;
      *) echo "opção desconhecida: $arg" >&2; exit 2 ;;
    esac
  done
  if ((second)); then
    export COMPOSE_PROFILES=segundo-tenant
  fi
  for entry in "${STACK[@]}"; do
    IFS="|" read -r project ports path <<<"$entry"
    if ((second)) && [[ -n "${SECOND_PORTS[$project]:-}" ]]; then
      ports="$ports ${SECOND_PORTS[$project]}"
    fi
    echo "==> $project"
    (cd "$ROOT/$project" && docker compose up -d $build)
    for port in $ports; do
      printf "    esperando :%s%s ... " "$port" "$path"
      if wait_http "http://localhost:$port$path"; then
        echo "ok"
      else
        echo "não respondeu em 6 minutos"
        echo "    veja: (cd $project && docker compose logs --tail 80)"
        exit 1
      fi
    done
  done
  echo
  status
}

down() {
  for ((i = ${#STACK[@]} - 1; i >= 0; i--)); do
    IFS="|" read -r project _ _ <<<"${STACK[$i]}"
    echo "==> $project"
    # Com o perfil: para também o segundo-tenant, se estiver no ar.
    (cd "$ROOT/$project" && COMPOSE_PROFILES=segundo-tenant docker compose down)
  done
}

status() {
  docker ps --format "table {{.Names}}\t{{.Status}}" | sort
  echo
  printf "%-18s %s\n" "serviço" "health"
  for entry in "${STACK[@]}"; do
    IFS="|" read -r project ports path <<<"$entry"
    # O segundo-tenant só aparece quando está no ar.
    if [[ -n "${SECOND_PORTS[$project]:-}" ]] && docker ps -q --filter "name=segundo" | grep -q .; then
      ports="$ports ${SECOND_PORTS[$project]}"
    fi
    for port in $ports; do
      code=$(curl -s -o /dev/null -w "%{http_code}" "http://localhost:$port$path" || true)
      printf "%-18s :%s %s\n" "$project" "$port" "$code"
    done
  done
  code=$(curl -s -o /dev/null -w "%{http_code}" "http://localhost:4200/" || true)
  printf "%-18s :%s %s\n" "wallet-console" "4200" "${code} (só com 'dev-stack.sh console' rodando)"
}

console() {
  cd "$ROOT/wallet-console"
  [[ -d node_modules ]] || npm ci
  # --host 0.0.0.0: aceita conexões de fora do servidor (Tailscale, Codespaces). Os nomes aceitos estão em
  # allowedHosts, no angular.json.
  exec npx ng serve --proxy-config proxy.conf.json --host 0.0.0.0
}

case "${1:-}" in
  up) shift; up "$@" ;;
  down) down ;;
  status) status ;;
  console) console ;;
  *)
    sed -n '2,13p' "$0" | sed 's/^# \{0,1\}//'
    exit 2
    ;;
esac
