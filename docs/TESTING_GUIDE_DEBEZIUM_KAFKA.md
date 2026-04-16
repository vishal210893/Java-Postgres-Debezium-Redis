# Testing Guide — Debezium Kafka CDC Pipeline (Phase 2)

## Architecture Flow

```
┌────────┐    ┌──────────────┐    ┌──────────────────────────────────────────────────────────────┐
│        │    │              │    │                    k3d Cluster (cdc-demo)                    │
│ Client │───>│ Spring Boot  │    │                                                              │
│ (curl/ │    │  Write API   │──────>┌────────────┐    ┌──────────────────┐    ┌───────────┐     │
│ Postman│    │ (port 8082)  │    │  │ PostgreSQL │───>│ Debezium Kafka   │───>│  Kafka    │     │
│  )     │    │              │    │  │  (pod)     │    │ Connect (pod)    │    │ (KRaft)   │     │
│        │    │              │    │  │  WAL log   │    │ Reads WAL via    │    │ (pod)     │     │
│        │    │              │    │  └────────────┘    │ logical replicat.│    └─────┬─────┘     │
│        │    │              │    │       ↑             └──────────────────┘          │          │
│        │    │              │    │  port-forward                                     │          │
│        │    │              │    │  5432:5432          Topics:                       │          │
│        │    │              │    │                     cdc_demo.public.users ────────┘          │
│        │    │              │    │                     cdc_demo.public.orders                   │
│        │    │              │    │                              │                               │
│        │    │ @KafkaListener│◄───── NodePort 30094 ───────────┘                                │
│        │    │ (kafka profile│    │                                                             │
│        │    │  consumes     │    │                    ┌─────────────────┐                      │
│        │    │  CDC events)  │───────────────────────────> Redis (pod)  │                       │
│        │    │              │    │  port-forward       │  user:{id}     │                       │
│        │    │  Read API    │◄──────────────────────────  order:{id}    │                       │
│        │    │ (port 8082)  │    │  6379:6379          │                │                       │
└────────┘    └──────────────┘    │                    └─────────────────┘                       │
                                  └──────────────────────────────────────────────────────────────┘
```

### Data Flow (Step by Step)

```
1. Client sends POST /api/users → Spring Boot Write API
2. Spring Boot saves User to PostgreSQL (INSERT into users table)
3. PostgreSQL writes the change to WAL (Write-Ahead Log) with wal_level=logical
4. Debezium Kafka Connect reads WAL via logical replication slot (pgoutput plugin)
5. Debezium publishes a CDC event to Kafka topic "cdc_demo.public.users":
   {
     "before": null,
     "after": {"id": 1, "username": "alice", ...},
     "source": {"table": "users", ...},
     "op": "c"   // c=create, u=update, d=delete, r=read(snapshot)
   }
6. Spring Boot @KafkaListener (KafkaCdcConsumer) consumes the event from Kafka
7. CdcEventTransformer parses the Debezium envelope:
   - Extracts "after" payload
   - Converts snake_case to camelCase (via Guava CaseFormat)
   - Converts Debezium timestamps (epoch millis) to ISO LocalDateTime
   - Decimals handled by Debezium's decimal.handling.mode=string config
   - Writes to Redis as: user:1 = {"id":1,"username":"alice",...}
8. Client sends GET /api/users/1 → Spring Boot Read API → Redis → returns JSON
```

### Difference from Phase 1

| Aspect | Phase 1 (Debezium Redis Sink) | Phase 2 (Debezium Kafka) |
|--------|-------------------------------|--------------------------|
| CDC Engine | Debezium Server (standalone) | Debezium Kafka Connect |
| Event Transport | Redis Streams | Kafka Topics |
| Consumer | RedisStreamConsumer | @KafkaListener (KafkaCdcConsumer) |
| Spring Profile | `debezium-redis-sink` | `kafka` |
| Extra Infra | None | Kafka (KRaft) |
| Event Replay | Limited (stream trimming) | Full replay (Kafka retention) |
| Consumer Groups | Redis consumer groups | Kafka consumer groups |

---

## Prerequisites

- **k3d** v5.x installed (`brew install k3d`)
- **Docker** running (Rancher Desktop or Docker Desktop)
- **kubectl** configured
- **Maven** 3.8+ with Java 21+ (`mvn --version`)
- **redis-cli** for manual inspection (`brew install redis`)

---

## Step 1: Start Infrastructure

### Option A: Using Helm (recommended)

```bash
make helm-setup-debezium-kafka
```

This creates the k3d cluster, builds the custom PostgreSQL image, and runs `helm install` with `values-debezium-kafka.yaml` — deploying Redis, PostgreSQL, Kafka, and Debezium Connect in one shot. The connector is automatically registered via a Helm post-install Job.

### Option B: Using kubectl (raw manifests)

```bash
make setup-debezium-kafka
```

This creates the k3d cluster and deploys each component individually via `kubectl apply`. The `debezium-kafka-install` step also **automatically registers the PostgreSQL connector** by POSTing `k8s/kafka/debezium-connect/register-connector.json` to the Kafka Connect REST API.

**Or step by step (kubectl only):**

```bash
make cluster-create           # 1. Create k3d cluster with port mappings
make redis-install            # 2. Deploy Redis
make postgres-install         # 3. Build + deploy PostgreSQL (wal_level=logical)
make kafka-install            # 4. Deploy Kafka (KRaft single-node, no Zookeeper)
make debezium-kafka-install   # 5. Deploy Debezium Kafka Connect + auto-register connector
```

**Note:** If you need to re-register the connector manually (e.g., after config change):

```bash
make debezium-kafka-register
# This POSTs k8s/kafka/debezium-connect/register-connector.json to http://localhost:30083/connectors
```

**Verify all pods are running:**

```bash
make status
# OR
kubectl get pods
```

Expected output:

```
NAME                                READY   STATUS    RESTARTS   AGE
redis-xxxxxxxxx-xxxxx               1/1     Running   0          2m
postgres-xxxxxxxxx-xxxxx            1/1     Running   0          2m
kafka-xxxxxxxxx-xxxxx               1/1     Running   0          1m
debezium-connect-xxxxxxxxx-xxxxx    1/1     Running   0          30s
```

**Verify Debezium connector is RUNNING:**

```bash
curl -s http://localhost:30083/connectors/cdc-postgres-connector/status | python3 -m json.tool
```

Expected:

```json
{
  "name": "cdc-postgres-connector",
  "connector": { "state": "RUNNING", ... },
  "tasks": [{ "id": 0, "state": "RUNNING", ... }]
}
```

If connector or task state is FAILED, check logs:

```bash
kubectl logs deploy/debezium-connect --tail=30
```

---

## Step 2: Start Port Forwards

In a **separate terminal**:

```bash
# If using Helm (service names: cdc-demo-postgres, cdc-demo-redis)
make helm-port-forward-all

# If using kubectl (service names: postgres-svc, redis-svc)
make port-forward-all
```

This maps:
- `localhost:5432` → PostgreSQL pod (for Spring Boot JPA)
- `localhost:6379` → Redis pod (for Spring Boot Redis client)

Kafka is accessed via NodePort `30094` (external listener) — no port-forward needed.

**Verify connectivity:**

```bash
# Test Redis
redis-cli -p 6379 ping
# Expected: PONG

# Test PostgreSQL
kubectl exec deploy/postgres -- psql -U postgres -d cdc_demo -c "SELECT 1;"
# Expected: 1

# Test Kafka Connect REST API
curl -s http://localhost:30083/connectors | python3 -m json.tool
# Expected: ["cdc-postgres-connector"]
```

---

## Step 3: Start Spring Boot Application

### Option A: From Terminal

In a **third terminal**:

```bash
export DB_PASSWORD=postgres
./mvnw spring-boot:run -Dspring-boot.run.profiles=kafka
```

### Option B: From IntelliJ IDEA

1. Open **Run/Debug Configurations** (top-right dropdown → Edit Configurations)
2. Select or create a **Spring Boot** run configuration for `CdcDemoApplication`
3. Set **Active profiles**: `kafka`
4. Set **Environment variables**: `DB_PASSWORD=postgres`
5. Alternatively, add **VM options**: `-Dspring.profiles.active=kafka`
6. Click **Run**

**IMPORTANT:** The `kafka` profile activates:
- `KafkaCdcConsumer` with `@KafkaListener` on topics `cdc_demo.public.users` and `cdc_demo.public.orders`
- `KafkaConfig` bean that creates `kafkaListenerContainerFactory` pointing to `127.0.0.1:30094`
- Kafka auto-configuration is NOT excluded (unlike the `debezium-redis-sink` profile)

Without this profile, the Kafka consumer will NOT start.

**Verify app is running:**

```bash
curl http://localhost:8082/actuator/health
```

Expected: `"status":"UP"` with `db: UP` and `redis: UP`.

**Verify CDC mode is active:**

```bash
curl http://localhost:8082/api/health/cdc
```

Expected: `"cdcMode":"kafka"`. If it shows `"cdcMode":"none"`, the profile is not set.

**Swagger UI:** Open http://localhost:8082/swagger-ui.html

---

## Step 4: Create Test Data (Write API → PostgreSQL)

### Create Users

```bash
# User 1: alice (ADMIN)
curl -s -X POST http://localhost:8082/api/users \
  -H "Content-Type: application/json" \
  -d '{"username":"alice","email":"alice@example.com","role":"ADMIN"}' | python3 -m json.tool

# User 2: bob (USER)
curl -s -X POST http://localhost:8082/api/users \
  -H "Content-Type: application/json" \
  -d '{"username":"bob","email":"bob@example.com","role":"USER"}' | python3 -m json.tool

# User 3: charlie (MANAGER)
curl -s -X POST http://localhost:8082/api/users \
  -H "Content-Type: application/json" \
  -d '{"username":"charlie","email":"charlie@example.com","role":"MANAGER"}' | python3 -m json.tool
```

### Create Orders

```bash
# Order 1: alice's laptop (PENDING by default)
curl -s -X POST http://localhost:8082/api/orders \
  -H "Content-Type: application/json" \
  -d '{"userId":1,"amount":299.99,"description":"Laptop"}' | python3 -m json.tool

# Order 2: alice's mouse (CONFIRMED)
curl -s -X POST http://localhost:8082/api/orders \
  -H "Content-Type: application/json" \
  -d '{"userId":1,"amount":49.99,"status":"CONFIRMED","description":"Mouse"}' | python3 -m json.tool

# Order 3: bob's monitor
curl -s -X POST http://localhost:8082/api/orders \
  -H "Content-Type: application/json" \
  -d '{"userId":2,"amount":999.00,"description":"Monitor"}' | python3 -m json.tool
```

---

## Step 5: Verify Data in PostgreSQL

```bash
kubectl exec deploy/postgres -- psql -U postgres -d cdc_demo -c "SELECT id, username, email, role FROM users ORDER BY id;"
```

Expected:

```
 id | username |       email          |    role
----+----------+----------------------+---------
  1 | alice    | alice@example.com    | ADMIN
  2 | bob      | bob@example.com      | USER
  3 | charlie  | charlie@example.com  | MANAGER
```

```bash
kubectl exec deploy/postgres -- psql -U postgres -d cdc_demo -c "SELECT id, user_id, amount, status, description FROM orders ORDER BY id;"
```

Expected:

```
 id | user_id | amount  | status    | description
----+---------+---------+-----------+-------------
  1 |       1 |  299.99 | PENDING   | Laptop
  2 |       1 |   49.99 | CONFIRMED | Mouse
  3 |       2 |  999.00 | PENDING   | Monitor
```

---

## Step 6: Verify Kafka Topics

### List topics

```bash
kubectl exec deploy/kafka -- /opt/kafka/bin/kafka-topics.sh --list --bootstrap-server localhost:9092
```

Expected (among others):

```
cdc_demo.public.users
cdc_demo.public.orders
```

### Check message count

```bash
kubectl exec deploy/kafka -- /opt/kafka/bin/kafka-get-offsets.sh --bootstrap-server localhost:9092 --topic cdc_demo.public.users
kubectl exec deploy/kafka -- /opt/kafka/bin/kafka-get-offsets.sh --bootstrap-server localhost:9092 --topic cdc_demo.public.orders
```

### Read a message from topic (optional)

```bash
kubectl exec deploy/kafka -- /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 \
  --topic cdc_demo.public.users \
  --from-beginning --max-messages 1 --timeout-ms 5000
```

---

## Step 7: Verify CDC Pipeline — Data in Redis

### Check Redis Keys

```bash
redis-cli -p 6379 KEYS "*" | sort
```

Expected:

```
order:1
order:2
order:3
user:1
user:2
user:3
```

### Inspect Individual Keys

```bash
redis-cli -p 6379 GET "user:1"
redis-cli -p 6379 GET "order:1"
```

Expected (user:1):

```json
{"id":1,"username":"alice","email":"alice@example.com","role":"ADMIN","createdAt":"2026-...","updatedAt":"2026-..."}
```

Expected (order:1):

```json
{"id":1,"userId":1,"amount":"299.99","status":"PENDING","description":"Laptop","createdAt":"2026-...","updatedAt":"2026-..."}
```

---

## Step 8: Verify Read API (Redis → Client)

### Get All Users

```bash
curl -s http://localhost:8082/api/users | python3 -m json.tool
```

### Get Single User

```bash
curl -s http://localhost:8082/api/users/1 | python3 -m json.tool
```

### Get All Orders

```bash
curl -s http://localhost:8082/api/orders | python3 -m json.tool
```

### Get Orders by User

```bash
curl -s http://localhost:8082/api/orders/user/1 | python3 -m json.tool
```

### CDC Health Check

```bash
curl -s http://localhost:8082/api/health/cdc | python3 -m json.tool
```

Expected:

```json
{
  "success": true,
  "data": {
    "cdcMode": "kafka",
    "redis": "UP"
  }
}
```

---

## Step 9: Test CDC Operations

### Test UPDATE — Change propagates via Kafka to Redis

```bash
# Update alice's role to SUPERADMIN
curl -s -X PUT http://localhost:8082/api/users/1 \
  -H "Content-Type: application/json" \
  -d '{"username":"alice","email":"alice@example.com","role":"SUPERADMIN"}' | python3 -m json.tool

# Verify in Redis (role should be SUPERADMIN now)
redis-cli -p 6379 GET "user:1"

# Verify via Read API
curl -s http://localhost:8082/api/users/1 | python3 -m json.tool
```

### Test PATCH Order Status — Status change propagates

```bash
# Ship alice's laptop
curl -s -X PATCH http://localhost:8082/api/orders/1/status \
  -H "Content-Type: application/json" \
  -d '{"status":"SHIPPED"}' | python3 -m json.tool

# Verify in Redis
redis-cli -p 6379 GET "order:1"

# Verify via Read API
curl -s http://localhost:8082/api/orders/1 | python3 -m json.tool
```

### Test DELETE — Key removed from Redis via Kafka CDC

```bash
# Delete charlie
curl -s -X DELETE http://localhost:8082/api/users/3 | python3 -m json.tool

# Verify key is gone from Redis
redis-cli -p 6379 GET "user:3"
# Expected: (nil)

# Verify via Read API (should return 404)
curl -s http://localhost:8082/api/users/3 | python3 -m json.tool
```

---

## Step 10: Inspect Kafka and Debezium Logs

### Debezium Kafka Connect logs

```bash
# View recent logs
kubectl logs deploy/debezium-connect --tail=20

# Stream logs in real-time
kubectl logs deploy/debezium-connect -f
```

### Kafka broker logs

```bash
kubectl logs deploy/kafka --tail=20
```

### Connector management (REST API)

```bash
# List all connectors
curl -s http://localhost:30083/connectors | python3 -m json.tool

# Connector status
curl -s http://localhost:30083/connectors/cdc-postgres-connector/status | python3 -m json.tool

# Pause connector
curl -s -X PUT http://localhost:30083/connectors/cdc-postgres-connector/pause

# Resume connector
curl -s -X PUT http://localhost:30083/connectors/cdc-postgres-connector/resume

# Delete connector (stops CDC)
curl -s -X DELETE http://localhost:30083/connectors/cdc-postgres-connector
```

---

## Troubleshooting

### Connector state is FAILED

```bash
curl -s http://localhost:30083/connectors/cdc-postgres-connector/status | python3 -m json.tool
```

Common causes:
- **PostgreSQL connection refused**: Check Postgres pod is running, WAL config is correct
- **Replication slot conflict**: Another Debezium instance holds the slot. Drop it:
  ```bash
  kubectl exec deploy/postgres -- psql -U postgres -d cdc_demo -c "SELECT pg_drop_replication_slot('cdc_demo_slot');"
  ```
- **Topic creation failed**: Check Kafka broker logs

### Data not appearing in Redis

1. **Check the Spring profile is active**: `curl -s http://localhost:8082/api/health/cdc` — must show `"cdcMode":"kafka"`. If it shows `"none"`, set `-Dspring.profiles.active=kafka`.
2. **Check Kafka topics have data**: `kubectl exec deploy/kafka -- /opt/kafka/bin/kafka-topics.sh --list --bootstrap-server localhost:9092`
3. **Check connector is RUNNING**: `curl -s http://localhost:30083/connectors/cdc-postgres-connector/status`
4. **Check Spring Boot logs for Kafka consumer activity** — look for `KafkaMessageListenerContainer` and `CdcEventTransformer` log lines
5. **Check Kafka bootstrap-servers**: The `kafka` profile uses `127.0.0.1:30094` (Kafka EXTERNAL listener via NodePort). Verify with: `nc -zv 127.0.0.1 30094`

### Kafka consumer not starting

- Ensure `@EnableKafka` is on the main application class
- Ensure `KafkaConfig` bean is active (has `@Profile("kafka")`)
- Check for `kafkaListenerContainerFactory` errors in logs

### Port-forward issues

```bash
# Stop all port-forwards
make port-forward-stop

# Restart
make port-forward-all
```

---

## Cleanup

```bash
# Stop Spring Boot: Ctrl+C in the app terminal

# Stop port-forwards
make port-forward-stop

# --- kubectl cleanup ---
# Remove only Kafka components (keep Postgres + Redis)
make debezium-kafka-uninstall
make kafka-uninstall

# OR delete everything (kubectl)
make teardown

# --- Helm cleanup ---
# Delete everything (Helm)
make helm-teardown
```
