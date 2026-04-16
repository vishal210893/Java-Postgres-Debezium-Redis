# CDC Write-Back Redis Cache — Design Spec

## Overview

A Spring Boot learning project demonstrating Change Data Capture (CDC) using Debezium to stream PostgreSQL WAL changes into Redis. Writes go to PostgreSQL via REST API; reads are served from Redis, populated automatically by Debezium CDC.

Two CDC pipeline modes implemented in phases:
- **Phase 1:** Debezium Server → Redis Streams → Spring Boot Stream Consumer → Redis
- **Phase 2:** Debezium Kafka Connect → Kafka → Spring Boot @KafkaListener → Redis

## Domain Entities

### User

```
users table
├── id          BIGSERIAL, PK
├── username    VARCHAR, UNIQUE, NOT NULL
├── email       VARCHAR, UNIQUE, NOT NULL
├── role        VARCHAR, NOT NULL (ADMIN, USER, MANAGER)
├── created_at  TIMESTAMP
└── updated_at  TIMESTAMP
```

### Order

```
orders table
├── id           BIGSERIAL, PK
├── user_id      BIGINT, FK → users.id
├── amount       DECIMAL(10,2), NOT NULL
├── status       VARCHAR (PENDING, CONFIRMED, SHIPPED, DELIVERED, CANCELLED)
├── description  VARCHAR
├── created_at   TIMESTAMP
└── updated_at   TIMESTAMP
```

### Redis Key Format

JSON Strings — one key per entity:

```
user:{id}   →  {"id":1,"username":"alice","email":"alice@example.com","role":"USER",...}
order:{id}  →  {"id":1,"userId":1,"amount":99.99,"status":"CONFIRMED",...}
```

## REST API Design

### Write API (PostgreSQL)

| Method | Path                        | Description             |
|--------|-----------------------------|-------------------------|
| POST   | /api/users                  | Create user             |
| PUT    | /api/users/{id}             | Update user             |
| DELETE | /api/users/{id}             | Delete user             |
| POST   | /api/orders                 | Create order            |
| PUT    | /api/orders/{id}            | Update order            |
| DELETE | /api/orders/{id}            | Delete order            |
| PATCH  | /api/orders/{id}/status     | Update order status     |

### Read API (Redis)

| Method | Path                        | Description                    |
|--------|-----------------------------|--------------------------------|
| GET    | /api/users/{id}             | Get user from Redis            |
| GET    | /api/users                  | List all users from Redis      |
| GET    | /api/orders/{id}            | Get order from Redis           |
| GET    | /api/orders                 | List all orders from Redis     |
| GET    | /api/orders/user/{userId}   | Get orders by user from Redis  |

### Observability

| Method | Path                        | Description                    |
|--------|-----------------------------|--------------------------------|
| GET    | /api/health/cdc             | CDC pipeline status            |
| GET    | /actuator/health            | Spring Boot health             |

- Write endpoints return the saved entity from Postgres (immediate confirmation)
- Read endpoints return data from Redis only — 404 if key not in Redis (no DB fallback)
- ApiResponse<T> wrapper on all endpoints
- Swagger UI via springdoc-openapi at /swagger-ui.html

## CDC Pipeline — Phase 1 (Debezium Server → Redis)

```
┌────────┐    ┌──────────────┐    ┌────────────────┐    ┌──────────────┐    ┌───────┐
│ Client │───>│ Spring Boot  │───>│  PostgreSQL     │───>│  Debezium    │───>│ Redis │
│        │    │ (Write API)  │    │  (k3d pod)      │    │  Server      │    │       │
└────────┘    └──────────────┘    │  WAL enabled    │    │  (k3d pod)   │    └───┬───┘
                                  └────────────────┘    └──────────────┘        │
              ┌──────────────┐                                                  │
              │ Spring Boot  │<─────────────────────────────────────────────────┘
              │ (Read API)   │  reads JSON strings from Redis
              └──────────────┘
```

### PostgreSQL (k3d)

- Custom Docker image based on `postgres:16` with `wal_level=logical` baked in
- Deployed as Deployment + Service (NodePort for host access)
- PVC for data persistence across pod restarts
- Database created via `POSTGRES_DB=cdc_demo` env var in Deployment spec
- Spring Boot `init.sql` for any additional setup (e.g., replication permissions)
- Table creation handled by Hibernate `ddl-auto: update`

### Debezium Server (k3d)

- Official `quay.io/debezium/server` image
- All config as env vars in Deployment spec (no ConfigMap):
  - Source: `io.debezium.connector.postgresql.PostgresConnector`
  - Sink: `io.debezium.server.redis.RedisChangeConsumer`
  - Postgres connection: `postgres-svc.default.svc.cluster.local:5432`
  - Redis connection: `host.k3d.internal:6379`
  - Table include list: `public.users,public.orders`
- Reads Postgres WAL via logical replication slot

### Data Flow

1. Debezium reads WAL and writes CDC events to Redis Streams
2. Spring Boot `RedisStreamConsumer` listens on the streams
3. `CdcEventTransformer` extracts the `after` payload from the Debezium envelope
4. Transforms to entity JSON and writes `user:{id}` / `order:{id}` keys
5. On DELETE events (where `after` is null), removes the key from Redis

## CDC Pipeline — Phase 2 (Debezium → Kafka → Spring Boot)

```
┌────────────────┐    ┌──────────────────┐    ┌───────────┐    ┌──────────────┐    ┌───────┐
│  PostgreSQL    │───>│ Debezium Kafka   │───>│   Kafka   │───>│ Spring Boot  │───>│ Redis │
│  (k3d pod)    │    │ Connect (k3d pod)│    │ (k3d pod) │    │ @KafkaListener│    │       │
│  WAL          │    └──────────────────┘    └───────────┘    └──────────────┘    └───────┘
└────────────────┘
```

### Kafka (k3d)

- Single-node Kafka using KRaft mode (no Zookeeper)
- `apache/kafka` image
- Deployed as Deployment + Service (NodePort)
- Topics auto-created by Debezium: `cdc_demo.public.users`, `cdc_demo.public.orders`

### Debezium Kafka Connect (k3d)

- Official `quay.io/debezium/connect` image
- Env vars for Kafka bootstrap server, Postgres connection
- Connector registered via Kafka Connect REST API (POST /connectors)
- Makefile target or Job runs the registration curl

### Spring Boot Changes

- Add `spring-kafka` dependency
- `@KafkaListener` on topics `cdc_demo.public.users` and `cdc_demo.public.orders`
- Reuses `CdcEventTransformer` for envelope → entity JSON conversion
- DELETE handling: `after` is null → remove key from Redis

### Profile Switching

- `--spring.profiles.active=debezium-server` → Phase 1 (Redis Stream consumer)
- `--spring.profiles.active=kafka` → Phase 2 (Kafka listener)
- Phase 1 profile activates `RedisStreamConsumer` bean
- Phase 2 profile activates `KafkaCdcConsumer` bean

## K8s Manifests

```
k8s/
├── postgres/
│   ├── Dockerfile              # FROM postgres:16, COPY postgresql.conf
│   ├── postgresql.conf         # wal_level=logical
│   ├── deployment.yaml
│   └── service.yaml            # NodePort
├── debezium-server/
│   ├── deployment.yaml         # env vars for source/sink config
│   └── service.yaml
├── kafka/
│   ├── deployment.yaml         # KRaft single-node
│   └── service.yaml            # NodePort
└── debezium-connect/
    ├── deployment.yaml
    ├── service.yaml
    └── register-connector.json # POST /connectors payload
```

## Makefile

```makefile
# Cluster
cluster-create              # k3d cluster create cdc-demo with port mappings
cluster-delete              # k3d cluster delete cdc-demo

# Postgres
postgres-image              # docker build custom postgres image
postgres-image-load         # k3d image import into cluster
postgres-install            # kubectl apply deployment + service
postgres-uninstall

# Phase 1
debezium-install            # kubectl apply debezium-server
debezium-uninstall

# Phase 2
kafka-install               # kubectl apply kafka
kafka-uninstall
debezium-kafka-install      # kubectl apply debezium-connect + register connector
debezium-kafka-uninstall

# App
app-build                   # ./mvnw clean compile
app-run                     # ./mvnw spring-boot:run
app-run-debezium-redis-sink # spring.profiles.active=debezium-redis-sink
app-run-debezium-kafka      # spring.profiles.active=kafka

# Setup
setup-debezium-redis-sink   # cluster-create + redis-install + postgres-install + debezium-install
setup-debezium-kafka        # cluster-create + redis-install + postgres-install + kafka-install + debezium-kafka-install
teardown                    # cluster-delete
```

## Project Structure

```
Java-Posgtres-Debezium-Redis/
├── pom.xml
├── checkstyle.xml
├── Makefile
├── README.md
├── API_DOCUMENTATION.md
├── CLAUDE.md
├── k8s/
├── src/main/
│   ├── java/com/example/cdc/
│   │   ├── CdcDemoApplication.java
│   │   ├── config/
│   │   │   └── RedisConfig.java
│   │   ├── controller/
│   │   │   ├── UserWriteController.java
│   │   │   ├── UserReadController.java
│   │   │   ├── OrderWriteController.java
│   │   │   └── OrderReadController.java
│   │   ├── dto/
│   │   │   ├── ApiResponse.java
│   │   │   ├── UserRequest.java
│   │   │   ├── UserResponse.java
│   │   │   ├── OrderRequest.java
│   │   │   ├── OrderResponse.java
│   │   │   └── OrderStatusRequest.java
│   │   ├── exception/
│   │   │   ├── GlobalExceptionHandler.java
│   │   │   ├── EntityNotFoundException.java
│   │   │   └── CdcDataNotAvailableException.java
│   │   ├── model/
│   │   │   ├── User.java
│   │   │   └── Order.java
│   │   ├── repository/
│   │   │   ├── UserRepository.java
│   │   │   └── OrderRepository.java
│   │   ├── service/
│   │   │   ├── UserWriteService.java
│   │   │   ├── UserReadService.java
│   │   │   ├── OrderWriteService.java
│   │   │   └── OrderReadService.java
│   │   └── consumer/
│   │       ├── CdcEventTransformer.java
│   │       ├── RedisStreamConsumer.java
│   │       └── KafkaCdcConsumer.java
│   └── resources/
│       ├── application.yaml
│       ├── application-debezium-server.yaml
│       ├── application-kafka.yaml
│       └── init.sql
└── src/test/
    └── java/com/example/cdc/
```

## Tech Stack

| Technology        | Version | Purpose                                    |
|-------------------|---------|--------------------------------------------|
| Java              | 21      | Language runtime                           |
| Spring Boot       | 4.0.5   | Application framework                     |
| Spring Data JPA   | (BOM)   | PostgreSQL ORM (Hibernate)                 |
| Spring Data Redis | (BOM)   | Redis client (Lettuce)                     |
| Spring Kafka      | (BOM)   | Kafka consumer (Phase 2)                   |
| PostgreSQL        | 16      | Primary database (WAL source)              |
| Redis             | 6+      | CDC-populated read cache                   |
| Debezium Server   | 2.7     | CDC engine Phase 1 (direct Redis sink)     |
| Debezium Connect  | 2.7     | CDC engine Phase 2 (Kafka Connect)         |
| Kafka (KRaft)     | 3.7     | Event streaming (Phase 2)                  |
| k3d               | 5.x     | Local K8s cluster                          |
| Lombok            | 1.18.x  | Boilerplate reduction                      |
| springdoc-openapi | 3.0.2   | Swagger UI / OpenAPI 3                     |
| Maven Checkstyle  | 3.6.0   | Code style enforcement                     |

## Conventions

- Lombok: @Data, @Builder, @Slf4j, @RequiredArgsConstructor
- Javadoc with ASCII diagrams on public classes/methods
- ApiResponse<T> on all endpoints
- GlobalExceptionHandler via @RestControllerAdvice
- Checkstyle: 150 char lines, 500 line files, 60 line methods, no star imports
- StringRedisSerializer on all channels
- Swagger UI at /swagger-ui.html
- App port: 8082 (avoids conflict with Java_Redis on 8081)
