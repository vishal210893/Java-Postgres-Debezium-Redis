.PHONY: cluster-create cluster-delete \
        postgres-image postgres-image-load postgres-install postgres-uninstall \
        debezium-install debezium-uninstall \
        kafka-install kafka-uninstall \
        debezium-kafka-install debezium-kafka-uninstall debezium-kafka-register \
        app-build app-run app-run-phase1 app-run-phase2 \
        all-phase1 all-phase2 all-clean status

# ==================== Cluster ====================

cluster-create:
	k3d cluster create cdc-demo \
		-p "30432:30432@server:0" \
		-p "30092:30092@server:0" \
		-p "30083:30083@server:0"
	@echo "k3d cluster 'cdc-demo' created"

cluster-delete:
	k3d cluster delete cdc-demo
	@echo "k3d cluster 'cdc-demo' deleted"

# ==================== PostgreSQL ====================

postgres-image:
	docker build -t cdc-postgres:latest k8s/postgres/
	@echo "PostgreSQL image built: cdc-postgres:latest"

postgres-image-load:
	k3d image import cdc-postgres:latest -c cdc-demo
	@echo "PostgreSQL image loaded into k3d cluster"

postgres-install: postgres-image postgres-image-load
	kubectl apply -f k8s/postgres/deployment.yaml
	kubectl apply -f k8s/postgres/service.yaml
	kubectl wait --for=condition=ready pod -l app=postgres --timeout=120s
	@echo "PostgreSQL installed and ready"

postgres-uninstall:
	kubectl delete -f k8s/postgres/service.yaml --ignore-not-found
	kubectl delete -f k8s/postgres/deployment.yaml --ignore-not-found
	kubectl delete pvc postgres-pvc --ignore-not-found
	@echo "PostgreSQL uninstalled"

# ==================== Debezium Server (Phase 1) ====================

debezium-install:
	kubectl apply -f k8s/debezium-server/deployment.yaml
	kubectl apply -f k8s/debezium-server/service.yaml
	kubectl wait --for=condition=ready pod -l app=debezium-server --timeout=120s
	@echo "Debezium Server installed and ready"

debezium-uninstall:
	kubectl delete -f k8s/debezium-server/service.yaml --ignore-not-found
	kubectl delete -f k8s/debezium-server/deployment.yaml --ignore-not-found
	@echo "Debezium Server uninstalled"

# ==================== Kafka (Phase 2) ====================

kafka-install:
	kubectl apply -f k8s/kafka/deployment.yaml
	kubectl apply -f k8s/kafka/service.yaml
	kubectl wait --for=condition=ready pod -l app=kafka --timeout=120s
	@echo "Kafka installed and ready"

kafka-uninstall:
	kubectl delete -f k8s/kafka/service.yaml --ignore-not-found
	kubectl delete -f k8s/kafka/deployment.yaml --ignore-not-found
	@echo "Kafka uninstalled"

# ==================== Debezium Kafka Connect (Phase 2) ====================

debezium-kafka-install:
	kubectl apply -f k8s/kafka/debezium-connect/deployment.yaml
	kubectl apply -f k8s/kafka/debezium-connect/service.yaml
	kubectl wait --for=condition=ready pod -l app=debezium-connect --timeout=120s
	@echo "Debezium Connect installed and ready"
	$(MAKE) debezium-kafka-register

debezium-kafka-register:
	@echo "Registering Debezium PostgreSQL connector..."
	@sleep 10
	curl -X POST http://localhost:30083/connectors \
		-H "Content-Type: application/json" \
		-d @k8s/kafka/debezium-connect/register-connector.json
	@echo "\nDebezium connector registered"

debezium-kafka-uninstall:
	kubectl delete -f k8s/kafka/debezium-connect/service.yaml --ignore-not-found
	kubectl delete -f k8s/kafka/debezium-connect/deployment.yaml --ignore-not-found
	@echo "Debezium Connect uninstalled"

# ==================== App ====================

app-build:
	./mvnw clean compile

app-run:
	./mvnw spring-boot:run

app-run-phase1:
	./mvnw spring-boot:run -Dspring-boot.run.profiles=debezium-server

app-run-phase2:
	./mvnw spring-boot:run -Dspring-boot.run.profiles=kafka

# ==================== Combo ====================

all-phase1: cluster-create postgres-install debezium-install
	@echo "Phase 1 infrastructure ready (PostgreSQL + Debezium Server)"
	@echo "Run: make app-run-phase1"

all-phase2: kafka-install debezium-kafka-install
	@echo "Phase 2 infrastructure ready (Kafka + Debezium Connect)"
	@echo "Run: make app-run-phase2"

all-clean: cluster-delete
	@echo "All infrastructure cleaned up"

# ==================== Status ====================

status:
	@echo "=== Pods ==="
	kubectl get pods
	@echo "\n=== Services ==="
	kubectl get svc
	@echo "\n=== PVCs ==="
	kubectl get pvc
