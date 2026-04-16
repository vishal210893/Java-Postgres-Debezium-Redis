.PHONY: help \
        cluster-create cluster-delete \
        redis-install redis-uninstall \
        postgres-image postgres-image-load postgres-install postgres-uninstall \
        debezium-install debezium-uninstall \
        kafka-install kafka-uninstall \
        debezium-kafka-install debezium-kafka-uninstall debezium-kafka-register \
        port-forward-postgres port-forward-redis port-forward-all port-forward-stop \
        app-stop app-build app-run app-run-debezium-redis-sink app-run-debezium-kafka \
        setup-debezium-redis-sink setup-debezium-kafka teardown status \
        helm-install-debezium-redis-sink helm-install-debezium-kafka \
        helm-uninstall helm-upgrade helm-status helm-port-forward-all \
        helm-setup-debezium-redis-sink helm-setup-debezium-kafka helm-teardown

# ==================== Help ====================
# Usage: make help
# Shows all available targets with descriptions.

help: ## Show this help message
	@echo "CDC Demo - Makefile Targets"
	@echo "=========================="
	@echo ""
	@echo "Quick Start (Debezium Redis Sink — direct WAL to Redis):"
	@echo "  1. make setup-debezium-redis-sink       # Create k3d cluster + deploy Redis, PostgreSQL, Debezium Server"
	@echo "  2. make port-forward-all                # Expose PostgreSQL(5432) and Redis(6379) to localhost"
	@echo "  3. DB_PASSWORD=postgres make app-run-debezium-redis-sink  # Start Spring Boot app"
	@echo ""
	@echo "Quick Start (Debezium Kafka — WAL to Kafka to app):"
	@echo "  1. make setup-debezium-kafka            # Create k3d cluster + deploy Redis, PostgreSQL, Kafka, Debezium Connect"
	@echo "  2. make port-forward-all                # Expose PostgreSQL(5432) and Redis(6379) to localhost"
	@echo "  3. DB_PASSWORD=postgres make app-run-debezium-kafka  # Start Spring Boot app"
	@echo ""
	@echo "Cleanup:"
	@echo "  make port-forward-stop     # Stop all port-forward processes"
	@echo "  make teardown              # Delete k3d cluster and all infrastructure"
	@echo ""
	@echo "--- Individual Targets ---"
	@echo ""
	@grep -hE '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-35s\033[0m %s\n", $$1, $$2}'

# ==================== Cluster ====================
# Creates or deletes the k3d Kubernetes cluster with port mappings
# for NodePort services (PostgreSQL, Kafka, Debezium Connect).

cluster-create: ## Create k3d cluster 'cdc-demo' with NodePort mappings
	k3d cluster create cdc-demo \
		-p "30432:30432@server:0" \
		-p "30092:30092@server:0" \
		-p "30094:30094@server:0" \
		-p "30083:30083@server:0"
	@echo "k3d cluster 'cdc-demo' created"

cluster-delete: ## Delete k3d cluster 'cdc-demo' and all resources inside it
	k3d cluster delete cdc-demo
	@echo "k3d cluster 'cdc-demo' deleted"

# ==================== Redis ====================
# Deploys Redis 7 as a pod in the k3d cluster.
# Used as the CDC sink — Debezium writes CDC events to Redis Streams,
# and the transformed data is stored as user:{id} / order:{id} keys.

redis-install: ## Deploy Redis pod + NodePort service into k3d cluster
	kubectl apply -f k8s/redis/deployment.yaml
	kubectl apply -f k8s/redis/service.yaml
	kubectl wait --for=condition=ready pod -l app=redis --timeout=120s
	@echo "Redis installed and ready"

redis-uninstall: ## Remove Redis deployment and service from k3d cluster
	kubectl delete -f k8s/redis/service.yaml --ignore-not-found
	kubectl delete -f k8s/redis/deployment.yaml --ignore-not-found
	@echo "Redis uninstalled"

# ==================== PostgreSQL ====================
# Builds a custom PostgreSQL 16 image with wal_level=logical enabled
# (required for Debezium CDC), loads it into k3d, and deploys it.
# Database 'cdc_demo' is created automatically via POSTGRES_DB env var.

postgres-image: ## Build custom PostgreSQL Docker image (skips if already exists)
	@if docker image inspect cdc-postgres:latest >/dev/null 2>&1; then \
		echo "PostgreSQL image already exists, skipping build"; \
	else \
		docker build -t cdc-postgres:latest k8s/postgres/; \
		echo "PostgreSQL image built: cdc-postgres:latest"; \
	fi

postgres-image-load: ## Import PostgreSQL image into k3d cluster (skips if already loaded)
	@if docker exec k3d-cdc-demo-server-0 crictl images 2>/dev/null | grep -q cdc-postgres; then \
		echo "PostgreSQL image already loaded in k3d, skipping import"; \
	else \
		k3d image import cdc-postgres:latest -c cdc-demo; \
		echo "PostgreSQL image loaded into k3d cluster"; \
	fi

postgres-install: postgres-image postgres-image-load ## Build, load, and deploy PostgreSQL (runs postgres-image + postgres-image-load first)
	kubectl apply -f k8s/postgres/deployment.yaml
	kubectl apply -f k8s/postgres/service.yaml
	kubectl wait --for=condition=ready pod -l app=postgres --timeout=120s
	@echo "PostgreSQL installed and ready"

postgres-uninstall: ## Remove PostgreSQL deployment, service, and PVC from k3d cluster
	kubectl delete -f k8s/postgres/service.yaml --ignore-not-found
	kubectl delete -f k8s/postgres/deployment.yaml --ignore-not-found
	kubectl delete pvc postgres-pvc --ignore-not-found
	@echo "PostgreSQL uninstalled"

# ==================== Debezium Server (Phase 1) ====================
# Phase 1 CDC pipeline: Debezium Server reads PostgreSQL WAL and writes
# CDC events directly to Redis Streams (no Kafka involved).
# Spring Boot's RedisStreamConsumer picks up events and transforms them
# into user:{id} / order:{id} Redis keys.

debezium-install: ## Deploy Debezium Server (Phase 1 — streams WAL directly to Redis)
	kubectl apply -f k8s/debezium-server/deployment.yaml
	kubectl apply -f k8s/debezium-server/service.yaml
	kubectl wait --for=condition=ready pod -l app=debezium-server --timeout=120s
	@echo "Debezium Server installed and ready"

debezium-uninstall: ## Remove Debezium Server deployment and service
	kubectl delete -f k8s/debezium-server/service.yaml --ignore-not-found
	kubectl delete -f k8s/debezium-server/deployment.yaml --ignore-not-found
	@echo "Debezium Server uninstalled"

# ==================== Kafka (Phase 2) ====================
# Phase 2 CDC pipeline: Kafka acts as the event bus between Debezium
# and Spring Boot. Single-node KRaft (no Zookeeper).
# Debezium Kafka Connect reads WAL → publishes to Kafka topics →
# Spring Boot @KafkaListener consumes and writes to Redis.

kafka-install: ## Deploy single-node Kafka (KRaft mode) for Phase 2 CDC pipeline
	kubectl apply -f k8s/kafka/deployment.yaml
	kubectl apply -f k8s/kafka/service.yaml
	kubectl wait --for=condition=ready pod -l app=kafka --timeout=120s
	@echo "Kafka installed and ready"

kafka-uninstall: ## Remove Kafka deployment and service
	kubectl delete -f k8s/kafka/service.yaml --ignore-not-found
	kubectl delete -f k8s/kafka/deployment.yaml --ignore-not-found
	@echo "Kafka uninstalled"

# ==================== Debezium Kafka Connect (Phase 2) ====================
# Deploys Debezium as a Kafka Connect connector (instead of standalone server).
# After deployment, registers the PostgreSQL connector via the Connect REST API.

debezium-kafka-install: ## Deploy Debezium Kafka Connect + register PostgreSQL connector (Phase 2)
	kubectl apply -f k8s/kafka/debezium-connect/deployment.yaml
	kubectl apply -f k8s/kafka/debezium-connect/service.yaml
	kubectl wait --for=condition=ready pod -l app=debezium-connect --timeout=120s
	@echo "Debezium Connect installed and ready"
	$(MAKE) debezium-kafka-register

debezium-kafka-register: ## Register Debezium PostgreSQL connector via Kafka Connect REST API
	@echo "Registering Debezium PostgreSQL connector..."
	@sleep 10
	curl -X POST http://localhost:30083/connectors \
		-H "Content-Type: application/json" \
		-d @k8s/kafka/debezium-connect/register-connector.json
	@echo "\nDebezium connector registered"

debezium-kafka-uninstall: ## Remove Debezium Kafka Connect deployment and service
	kubectl delete -f k8s/kafka/debezium-connect/service.yaml --ignore-not-found
	kubectl delete -f k8s/kafka/debezium-connect/deployment.yaml --ignore-not-found
	@echo "Debezium Connect uninstalled"

# ==================== Port Forward ====================
# Spring Boot runs on the host (outside k3d), so it needs port-forward
# to reach PostgreSQL and Redis running inside the k3d cluster.
# Debezium (inside k3d) talks to PostgreSQL and Redis via cluster DNS — no port-forward needed.

port-forward-postgres: ## Forward localhost:5432 → PostgreSQL pod (run in separate terminal, Ctrl+C to stop)
	@echo "Port-forwarding PostgreSQL: localhost:5432 -> postgres pod:5432"
	@echo "Run this in a separate terminal (Ctrl+C to stop)"
	kubectl port-forward svc/postgres-svc 5432:5432

port-forward-redis: ## Forward localhost:6379 → Redis pod (run in separate terminal, Ctrl+C to stop)
	@echo "Port-forwarding Redis: localhost:6379 -> redis pod:6379"
	@echo "Run this in a separate terminal (Ctrl+C to stop)"
	kubectl port-forward svc/redis-svc 6379:6379

port-forward-all: ## Forward both PostgreSQL(5432) and Redis(6379) in background
	@echo "Starting port-forwards in background..."
	kubectl port-forward svc/postgres-svc 5432:5432 &
	kubectl port-forward svc/redis-svc 6379:6379 &
	@echo "Port-forwards running: PostgreSQL(5432), Redis(6379)"
	@echo "Run 'make port-forward-stop' to stop all"

port-forward-stop: ## Kill all running kubectl port-forward processes
	@echo "Killing all kubectl port-forward processes..."
	-pkill -f "kubectl port-forward" || true
	@echo "Port forwards stopped"

# ==================== App ====================
# Spring Boot application commands. The app runs on the host (not in k3d).
# Requires port-forward-all to be running so it can reach PostgreSQL and Redis.
# Set DB_PASSWORD=postgres before running (e.g., DB_PASSWORD=postgres make app-run-debezium-redis-sink).

app-stop: ## Stop Spring Boot app running on port 8082
	-lsof -ti:8082 | xargs kill -9 2>/dev/null || true
	-pkill -f "spring-boot:run" 2>/dev/null || true
	@echo "App stopped"

app-build: ## Build the Spring Boot app (Maven compile + checkstyle)
	./mvnw clean compile

app-run: ## Run Spring Boot app with default profile (no CDC consumer active)
	./mvnw spring-boot:run

app-run-debezium-redis-sink: ## Run Spring Boot app with 'debezium-redis-sink' profile (Redis Stream consumer)
	./mvnw spring-boot:run -Dspring-boot.run.profiles=debezium-redis-sink

app-run-debezium-kafka: ## Run Spring Boot app with 'kafka' profile (Kafka consumer)
	./mvnw spring-boot:run -Dspring-boot.run.profiles=kafka

# ==================== Setup ====================
# One-command setup for each CDC pipeline. These chain individual targets together.
# Each setup is fully self-contained — no need to run one before the other.

setup-debezium-redis-sink: cluster-create redis-install postgres-install debezium-install ## Set up Debezium Redis Sink: k3d + Redis + PostgreSQL + Debezium Server
	@echo ""
	@echo "=============================================="
	@echo "  Debezium Redis Sink infrastructure ready!"
	@echo "  (Redis + PostgreSQL + Debezium Server)"
	@echo "=============================================="
	@echo ""
	@echo "Next steps:"
	@echo "  1. Run: make port-forward-all"
	@echo "  2. Run: DB_PASSWORD=postgres make app-run-debezium-redis-sink"
	@echo "  3. Open: http://localhost:8082/swagger-ui.html"
	@echo ""

setup-debezium-kafka: cluster-create redis-install postgres-install kafka-install debezium-kafka-install ## Set up Debezium Kafka: k3d + Redis + PostgreSQL + Kafka + Debezium Connect
	@echo ""
	@echo "=============================================="
	@echo "  Debezium Kafka infrastructure ready!"
	@echo "  (Redis + PostgreSQL + Kafka + Debezium Connect)"
	@echo "=============================================="
	@echo ""
	@echo "Next steps:"
	@echo "  1. Run: make port-forward-all"
	@echo "  2. Run: DB_PASSWORD=postgres make app-run-debezium-kafka"
	@echo "  3. Open: http://localhost:8082/swagger-ui.html"
	@echo ""

teardown: cluster-delete ## Tear down EVERYTHING: delete k3d cluster and all resources
	@echo "All infrastructure cleaned up"

# ==================== Status ====================

status: ## Show current state of pods, services, and PVCs in the k3d cluster
	@echo "=== Pods ==="
	kubectl get pods
	@echo "\n=== Services ==="
	kubectl get svc
	@echo "\n=== PVCs ==="
	kubectl get pvc

# ==================== Helm Targets ====================
# Import Helm-based targets from make/helm.mk.
# Run 'make helm-setup-debezium-redis-sink' or 'make helm-setup-debezium-kafka' to use Helm.
include make/helm.mk
