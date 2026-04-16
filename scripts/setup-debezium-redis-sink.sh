#!/bin/bash
# =============================================================================
# Setup Script — Debezium Redis Sink (Infrastructure Only)
# =============================================================================
# Sets up the k3d cluster, deploys Redis, PostgreSQL, Debezium Server,
# and starts port-forwards. Does NOT start the Spring Boot app —
# run it from your IDE with profile: debezium-redis-sink
#
# Architecture: PostgreSQL → Debezium Server → Redis Streams → Spring Boot → Redis Keys
#
# Usage:
#   ./scripts/setup-debezium-redis-sink.sh              # kubectl mode (default)
#   ./scripts/setup-debezium-redis-sink.sh --helm       # Helm mode
# =============================================================================

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$PROJECT_DIR"

MODE="${1:---kubectl}"

green()  { printf "\033[32m%s\033[0m\n" "$1"; }
red()    { printf "\033[31m%s\033[0m\n" "$1"; }
bold()   { printf "\033[1m%s\033[0m\n" "$1"; }

# --- Step 1: Clean up any existing environment ---

bold "=== Step 1: Cleanup existing environment ==="
pkill -f "kubectl port-forward" 2>/dev/null || true

if k3d cluster list 2>/dev/null | grep -q cdc-demo; then
    echo "Deleting existing k3d cluster..."
    helm uninstall cdc-demo 2>/dev/null || true
    k3d cluster delete cdc-demo 2>/dev/null || true
fi
green "Clean slate"
echo ""

# --- Step 2: Start infrastructure ---

bold "=== Step 2: Start infrastructure ==="
if [ "$MODE" = "--helm" ]; then
    echo "Using Helm..."
    make helm-setup-debezium-redis-sink
else
    echo "Using kubectl..."
    make setup-debezium-redis-sink
fi
green "Infrastructure ready"
echo ""

# --- Step 3: Start port-forwards ---

bold "=== Step 3: Start port-forwards ==="
if [ "$MODE" = "--helm" ]; then
    make helm-port-forward-all
else
    make port-forward-all
fi

# Wait for port-forwards to be ready
sleep 2
if redis-cli -p 6379 ping 2>/dev/null | grep -q PONG; then
    green "Port-forwards ready (Redis: PONG)"
else
    red "Port-forward to Redis failed"
    exit 1
fi
echo ""

# --- Step 4: Verify pods ---

bold "=== Step 4: Verify pods ==="
kubectl get pods
echo ""

# --- Summary ---

bold "=== Environment Ready ==="
echo "  k3d cluster:   cdc-demo (running)"
echo "  Pods:          $(kubectl get pods --no-headers 2>/dev/null | wc -l | tr -d ' ') running"
echo "  Port-forwards: PostgreSQL(5432), Redis(6379)"
echo ""
bold "Next steps:"
echo "  1. Start Spring Boot from your IDE with:"
echo "     - Active profile:       debezium-redis-sink"
echo "     - Environment variable: DB_PASSWORD=postgres"
echo "  2. Run tests:  ./scripts/test-cdc-pipeline.sh"
echo ""
echo "Cleanup:"
if [ "$MODE" = "--helm" ]; then
    echo "  make port-forward-stop && make helm-teardown"
else
    echo "  make port-forward-stop && make teardown"
fi
