# ==================== Helm Targets ====================
# Included by the main Makefile. Adds Helm-based install/uninstall/upgrade
# as an alternative to raw kubectl apply.
#
# Helm service names use <release>-<component> pattern (e.g., cdc-demo-postgres).

HELM_CHART     := helm/cdc-demo
HELM_RELEASE   := cdc-demo
HELM_NAMESPACE := default

# ==================== Helm Install ====================
# Installs the umbrella Helm chart with the appropriate values file.
# Each mode enables only the components it needs.

helm-install-debezium-redis-sink: ## Helm install: Redis + PostgreSQL + Debezium Server
	helm install $(HELM_RELEASE) $(HELM_CHART) \
		-f $(HELM_CHART)/values-debezium-redis-sink.yaml \
		-n $(HELM_NAMESPACE) \
		--wait --timeout 120s
	@echo "Helm release '$(HELM_RELEASE)' installed (Debezium Redis Sink mode)"

helm-install-debezium-kafka: ## Helm install: Redis + PostgreSQL + Kafka + Debezium Connect
	helm install $(HELM_RELEASE) $(HELM_CHART) \
		-f $(HELM_CHART)/values-debezium-kafka.yaml \
		-n $(HELM_NAMESPACE) \
		--wait --timeout 120s
	@echo "Helm release '$(HELM_RELEASE)' installed (Debezium Kafka mode)"

# ==================== Helm Management ====================

helm-uninstall: ## Uninstall Helm release (keeps k3d cluster running)
	helm uninstall $(HELM_RELEASE) -n $(HELM_NAMESPACE)
	@echo "Helm release '$(HELM_RELEASE)' uninstalled"

helm-upgrade: ## Upgrade Helm release — usage: make helm-upgrade VALUES=<values-file>
	@if [ -z "$(VALUES)" ]; then \
		echo "ERROR: VALUES not set."; \
		echo "Usage: make helm-upgrade VALUES=helm/cdc-demo/values-debezium-redis-sink.yaml"; \
		exit 1; \
	fi
	helm upgrade $(HELM_RELEASE) $(HELM_CHART) \
		-f $(VALUES) \
		-n $(HELM_NAMESPACE) \
		--wait --timeout 120s
	@echo "Helm release '$(HELM_RELEASE)' upgraded"

helm-status: ## Show Helm release status, values, and deployed resources
	@echo "=== Helm Release ==="
	helm status $(HELM_RELEASE) -n $(HELM_NAMESPACE) 2>/dev/null || echo "No release found"
	@echo ""
	@echo "=== Helm Values ==="
	helm get values $(HELM_RELEASE) -n $(HELM_NAMESPACE) 2>/dev/null || echo "No release found"
	@echo ""
	@echo "=== Kubernetes Resources ==="
	kubectl get pods,svc,pvc -n $(HELM_NAMESPACE) 2>/dev/null

# ==================== Helm Port Forward ====================
# Helm service names: <release>-postgres, <release>-redis

helm-port-forward-all: ## Forward PostgreSQL(5432) and Redis(6379) using Helm service names
	@echo "Starting port-forwards (Helm service names)..."
	kubectl port-forward svc/$(HELM_RELEASE)-postgres 5432:5432 -n $(HELM_NAMESPACE) &
	kubectl port-forward svc/$(HELM_RELEASE)-redis 6379:6379 -n $(HELM_NAMESPACE) &
	@echo "Port-forwards running: PostgreSQL(5432), Redis(6379)"
	@echo "Run 'make port-forward-stop' to stop all"

# ==================== Helm Setup (combo) ====================
# Full setup: k3d cluster + postgres image + helm install

helm-setup-debezium-redis-sink: cluster-create postgres-image postgres-image-load helm-install-debezium-redis-sink ## Full Helm setup: k3d + Redis + PostgreSQL + Debezium Server
	@echo ""
	@echo "=============================================="
	@echo "  Debezium Redis Sink ready! (Helm)"
	@echo "  Release: $(HELM_RELEASE)"
	@echo "=============================================="
	@echo ""
	@echo "Next steps:"
	@echo "  1. Run: make helm-port-forward-all"
	@echo "  2. Run: DB_PASSWORD=postgres make app-run-debezium-redis-sink"
	@echo "  3. Open: http://localhost:8082/swagger-ui.html"
	@echo ""

helm-setup-debezium-kafka: cluster-create postgres-image postgres-image-load helm-install-debezium-kafka ## Full Helm setup: k3d + Redis + PostgreSQL + Kafka + Debezium Connect
	@echo ""
	@echo "=============================================="
	@echo "  Debezium Kafka ready! (Helm)"
	@echo "  Release: $(HELM_RELEASE)"
	@echo "=============================================="
	@echo ""
	@echo "Next steps:"
	@echo "  1. Run: make helm-port-forward-all"
	@echo "  2. Run: DB_PASSWORD=postgres make app-run-debezium-kafka"
	@echo "  3. Open: http://localhost:8082/swagger-ui.html"
	@echo ""

helm-teardown: helm-uninstall cluster-delete ## Uninstall Helm release + delete k3d cluster
	@echo "All Helm infrastructure cleaned up"
