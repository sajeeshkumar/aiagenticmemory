#!/usr/bin/env bash
# Loads OPENROUTER_API_KEY (and friends) from .env, makes sure Redis + pgvector are up, then
# starts the agent.
#   ./run.sh                              -> interactive REPL, stage 3 (latest), random ids
#   ./run.sh --demo                       -> scripted demo for the latest stage
#   ./run.sh --stage=1 --demo             -> same demo shape against the no-memory agent
#   ./run.sh --stage=2 --demo             -> single-session demo against the session-state agent
#   ./run.sh --customer=cust-abc --session=sess-abc123   -> resume specific ids
set -euo pipefail
cd "$(dirname "$0")"
[ -f .env ] && set -a && . ./.env && set +a
: "${OPENROUTER_API_KEY:?set OPENROUTER_API_KEY in .env or the environment}"

# Starts a compose service if nothing already answers on the given port, waiting up to ~10s.
ensure_port_up() {
  local name="$1" port="$2"
  if (exec 3<>/dev/tcp/localhost/"$port") 2>/dev/null; then
    return
  fi
  if command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
    echo "Starting $name (docker compose)..."
    docker compose up -d "$name"
    for _ in $(seq 1 20); do
      (exec 3<>/dev/tcp/localhost/"$port") 2>/dev/null && return
      sleep 0.5
    done
    echo "WARNING: $name still isn't answering on localhost:$port after waiting." >&2
  else
    echo "WARNING: nothing answering on localhost:$port and Docker isn't available/running." >&2
    echo "         Start Docker Desktop and re-run, or point the matching env vars at your own instance." >&2
  fi
}

ensure_port_up redis 6379      # Stage 2+ session state
ensure_port_up pgvector 5432   # Stage 3+ episodic memory

exec mvn -q spring-boot:run -Dspring-boot.run.arguments="$*"
