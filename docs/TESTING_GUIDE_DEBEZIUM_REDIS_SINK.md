# Phase 1 Testing Guide — Debezium Server CDC Pipeline

## Architecture Flow

```
┌────────┐    ┌──────────────┐    ┌──────────────────────────────────────────────┐
│        │    │              │    │              k3d Cluster (cdc-demo)           │
│ Client │───>│ Spring Boot  │    │                                              │
│ (curl/ │    │  Write API   │──────>┌────────────┐    ┌──────────────────┐      │
│ Postman│    │ (port 8082)  │    │  │ PostgreSQL │───>│ Debezium Server  │      │
│  )     │    │              │    │  │  (pod)     │    │  (pod)           │      │
│        │    │              │    │  │  WAL log   │    │  Reads WAL       │      │
│        │    │              │    │  └────────────┘    │  Writes to Redis │      │
│        │    │              │    │       ↑             │  Streams         │      │
│        │    │              │    │  port-forward       └────────┬─────────┘      │
│        │    │              │    │  5432:5432                   │                │
│        │    │              │    │                    ┌─────────▼────────┐       │
│        │    │              │    │                    │  Redis (pod)     │       │
│        │    │  Read API    │◄──────────────────────────  user:{id}     │       │
│        │    │ (port 8082)  │    │  port-forward      │  order:{id}     │       │
│        │    │              │    │  6379:6379          │                 │       │
└────────┘    └──────────────┘    │                    └─────────────────┘       │
                                  └──────────────────────────────────────────────┘
```

### Data Flow (Step by Step)

```
1. Client sends POST /api/users → Spring Boot Write API
2. Spring Boot saves User to PostgreSQL (INSERT into users table)
3. PostgreSQL writes the change to WAL (Write-Ahead Log) with wal_level=logical
4. Debezium Server reads WAL via logical replication slot (pgoutput plugin)
5. Debezium transforms the WAL entry into a CDC event envelope:
   {
     "before": null,
     "after": {"id": 1, "username": "alice", ...},
     "source": {"table": "users", ...},
     "op": "c"   // c=create, u=update, d=delete
   }
6. Debezium writes the CDC event to Redis Stream "cdc_demo.public.users"
7. Spring Boot RedisStreamConsumer picks up the event from the stream
8. CdcEventTransformer parses the Debezium envelope:
   - Extracts "after" payload
   - Converts snake_case to camelCase (via Guava CaseFormat)
   - Converts Debezium timestamps (epoch millis) to ISO LocalDateTime
   - Decimals handled by Debezium's decimal.handling.mode=string config
   - Writes to Redis as: user:1 = {"id":1,"username":"alice",...}
9. Client sends GET /api/users/1 → Spring Boot Read API → Redis → returns JSON
```

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
make helm-setup-debezium-redis-sink
```

This creates the k3d cluster, builds the custom PostgreSQL image, and runs `helm install` with `values-debezium-redis-sink.yaml` — deploying Redis, PostgreSQL, and Debezium Server in one shot.

### Option B: Using kubectl (raw manifests)

```bash
make setup-debezium-redis-sink
```

This runs:
1. `k3d cluster create cdc-demo` with port mappings
2. Deploys Redis pod + NodePort service
3. Builds custom PostgreSQL image (wal_level=logical), loads into k3d, deploys
4. Deploys Debezium Server pointing to PostgreSQL and Redis inside the cluster

**Verify all pods are running:**

```bash
make status
# OR
kubectl get pods
```

Expected output:

```
NAME                               READY   STATUS    RESTARTS   AGE
redis-xxxxxxxxx-xxxxx              1/1     Running   0          1m
postgres-xxxxxxxxx-xxxxx           1/1     Running   0          1m
debezium-server-xxxxxxxxx-xxxxx    1/1     Running   0          30s
```

If Debezium Server is in CrashLoopBackOff, check logs:

```bash
kubectl logs deploy/debezium-server --tail=20
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

**Verify connectivity:**

```bash
# Test Redis
redis-cli -p 6379 ping
# Expected: PONG

# Test PostgreSQL
kubectl exec deploy/postgres -- psql -U postgres -d cdc_demo -c "SELECT 1;"
# Expected: 1
```

---

## Step 3: Start Spring Boot Application

### Option A: From Terminal

In a **third terminal**:

```bash
export DB_PASSWORD=postgres
./mvnw spring-boot:run -Dspring-boot.run.profiles=debezium-redis-sink
```

### Option B: From IntelliJ IDEA

1. Open **Run/Debug Configurations** (top-right dropdown → Edit Configurations)
2. Select or create a **Spring Boot** run configuration for `CdcDemoApplication`
3. Set **Active profiles**: `debezium-redis-sink`
4. Set **Environment variables**: `DB_PASSWORD=postgres`
5. Alternatively, add **VM options**: `-Dspring.profiles.active=debezium-redis-sink`
6. Click **Run**

**IMPORTANT:** The `debezium-redis-sink` profile activates the `RedisStreamConsumer` which reads CDC events from Redis Streams and writes them as `user:{id}` / `order:{id}` keys. Without this profile, the CDC consumer will NOT start and no data will appear in Redis keys.

**Verify app is running:**

```bash
curl http://localhost:8082/actuator/health
```

Expected: `"status":"UP"` with `db: UP` and `redis: UP`.

**Verify CDC mode is active:**

```bash
curl http://localhost:8082/api/health/cdc
```

Expected:

```json
{
  "success": true,
  "data": {
    "cdcMode": "debezium-redis-sink",
    "redis": "UP"
  }
}
```

If it shows `"cdcMode":"none"`, the profile is not set.

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

### Update Order Status

```bash
# Ship alice's laptop
curl -s -X PATCH http://localhost:8082/api/orders/1/status \
  -H "Content-Type: application/json" \
  -d '{"status":"SHIPPED"}' | python3 -m json.tool
```

### Update User

```bash
# Promote alice to SUPERADMIN
curl -s -X PUT http://localhost:8082/api/users/1 \
  -H "Content-Type: application/json" \
  -d '{"username":"alice","email":"alice@example.com","role":"SUPERADMIN"}' | python3 -m json.tool
```

---

## Step 5: Verify Data in PostgreSQL

```bash
kubectl exec deploy/postgres -- psql -U postgres -d cdc_demo -c "SELECT id, username, email, role FROM users ORDER BY id;"
```

Expected:

```
 id | username |       email          |    role
----+----------+----------------------+------------
  1 | alice    | alice@example.com    | SUPERADMIN
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
  1 |       1 |  299.99 | SHIPPED   | Laptop
  2 |       1 |   49.99 | CONFIRMED | Mouse
  3 |       2 |  999.00 | PENDING   | Monitor
```

---

## Step 6: Verify CDC Pipeline — Data in Redis

### Check Redis Keys

```bash
redis-cli -p 6379 KEYS "*" | grep -v "cdc_demo"
```

Expected:

```
user:1
user:2
user:3
order:1
order:2
order:3
```

### Inspect Individual Keys

```bash
redis-cli -p 6379 GET "user:1"
redis-cli -p 6379 GET "order:1"
```

Expected (user:1):

```json
{"id":1,"username":"alice","email":"alice@example.com","role":"SUPERADMIN","createdAt":"2026-...","updatedAt":"2026-..."}
```

Expected (order:1):

```json
{"id":1,"userId":1,"amount":299.99,"status":"SHIPPED","description":"Laptop","createdAt":"2026-...","updatedAt":"2026-..."}
```

### Check Redis Streams (Debezium CDC events)

```bash
redis-cli -p 6379 XLEN "cdc_demo.public.users"
redis-cli -p 6379 XLEN "cdc_demo.public.orders"
```

---

## Step 7: Verify Read API (Redis → Client)

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

---

## Step 8: Test CDC Operations

### Test UPDATE — Change propagates to Redis

```bash
# Update bob's role
curl -s -X PUT http://localhost:8082/api/users/2 \
  -H "Content-Type: application/json" \
  -d '{"username":"bob","email":"bob@example.com","role":"ADMIN"}' | python3 -m json.tool

# Verify in Redis (role should be ADMIN now)
redis-cli -p 6379 GET "user:2"

# Verify via Read API
curl -s http://localhost:8082/api/users/2 | python3 -m json.tool
```

### Test DELETE — Key removed from Redis

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

## Step 9: Inspect Debezium Server Logs

```bash
# View recent logs
kubectl logs deploy/debezium-server --tail=20

# Stream logs in real-time
kubectl logs deploy/debezium-server -f
```

Look for messages like:
- `Processing messages` — Debezium is actively reading WAL
- `records sent during previous` — Shows how many CDC events were processed

---

## Troubleshooting

### Debezium Server CrashLoopBackOff

```bash
kubectl logs deploy/debezium-server --tail=30
```

Common causes:
- **Redis connection refused**: Check Redis pod is running and service exists
- **PostgreSQL connection refused**: Check Postgres pod and WAL config
- **Replication slot issues**: `kubectl exec deploy/postgres -- psql -U postgres -d cdc_demo -c "SELECT * FROM pg_replication_slots;"`

### Data not appearing in Redis

1. **Check the Spring profile is active**: `curl -s http://localhost:8082/api/health/cdc` — must show `"cdcMode":"debezium-redis-sink"`. If it shows `"none"`, the app is running without the profile. Set `-Dspring.profiles.active=debezium-redis-sink` in your IDE or terminal.
2. Check Debezium is running: `kubectl get pods`
3. Check Redis Streams have data: `redis-cli -p 6379 XLEN "cdc_demo.public.users"`
4. Check Spring Boot consumer logs (look for CDC INFO messages)
5. Check consumer group: `redis-cli -p 6379 XINFO GROUPS "cdc_demo.public.users"`

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

# Delete everything (kubectl)
make teardown

# Delete everything (Helm)
make helm-teardown
```
