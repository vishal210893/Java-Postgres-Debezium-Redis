# CDC Demo - Postgres, Debezium, Redis

![Java](https://img.shields.io/badge/Java-21-blue?logo=openjdk)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.0.5-green?logo=springboot)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-blue?logo=postgresql)
![Redis](https://img.shields.io/badge/Redis-7+-red?logo=redis)
![Debezium](https://img.shields.io/badge/Debezium-2.7-orange)
![Kafka](https://img.shields.io/badge/Kafka-3.7-black?logo=apachekafka)
![Kubernetes](https://img.shields.io/badge/k3d-5.x-purple?logo=kubernetes)
![Helm](https://img.shields.io/badge/Helm-3-blue?logo=helm)
![License](https://img.shields.io/badge/License-MIT-yellow)

A hands-on Spring Boot project for learning **Change Data Capture (CDC)** using Debezium to stream PostgreSQL WAL changes into Redis. Writes go to PostgreSQL via REST API, Debezium captures changes from the WAL, and reads are served from Redis.

---

## Overview

This project demonstrates a real-world CDC pipeline where:
- The **Write API** saves data to PostgreSQL
- **Debezium** reads the PostgreSQL Write-Ahead Log (WAL) and streams change events
- Change events are transformed and written to **Redis** as JSON key-value pairs
- The **Read API** serves data directly from Redis (CDC-populated cache)

### What You Will Learn

- **Change Data Capture (CDC)** -- How Debezium reads PostgreSQL WAL and streams changes in real-time
- **Debezium Redis Sink** -- Direct CDC pipeline: PostgreSQL WAL --> Debezium Server --> Redis Streams --> Spring Boot consumer --> Redis keys
- **Debezium Kafka Pipeline** -- Production CDC pipeline: PostgreSQL WAL --> Debezium Kafka Connect --> Kafka --> Spring Boot @KafkaListener --> Redis keys
- **CQRS Pattern** -- Separate write path (PostgreSQL) and read path (Redis) with eventual consistency via CDC
- **Kubernetes Deployment** -- Running PostgreSQL, Redis, Kafka, and Debezium as containers in a k3d cluster
- **Helm Charts** -- Umbrella chart with subcharts for each component, toggled via values files
- **Spring Profiles** -- Switching between CDC modes using `debezium-redis-sink` and `kafka` profiles

### Architecture

Two CDC pipeline modes are available:

**Debezium Redis Sink (direct)**

```
Client --> Spring Boot (Write API) --> PostgreSQL (WAL) --> Debezium Server --> Redis Streams
                                                                                    |
                                                              Spring Boot (RedisStreamConsumer)
                                                                                    |
                                                                              Redis Keys
                                                                                    |
           Spring Boot (Read API) <-----------------------------------------------------
```

**Debezium Kafka (production-grade)**

```
Client --> Spring Boot (Write API) --> PostgreSQL (WAL) --> Debezium Kafka Connect --> Kafka
                                                                                        |
                                                              Spring Boot (@KafkaListener)
                                                                                        |
                                                                                  Redis Keys
                                                                                        |
           Spring Boot (Read API) <---------------------------------------------------------
```

---

## Tech Stack

| Technology        | Version | Purpose                                      |
|-------------------|---------|----------------------------------------------|
| Java              | 21      | Language runtime                             |
| Spring Boot       | 4.0.5   | Application framework                        |
| Spring Data JPA   | (BOM)   | PostgreSQL ORM (Hibernate)                   |
| Spring Data Redis | (BOM)   | Redis client (Lettuce)                       |
| Spring Kafka      | (BOM)   | Kafka consumer (Debezium Kafka mode)         |
| PostgreSQL        | 16      | Primary database (WAL source for CDC)        |
| Redis             | 7+      | CDC-populated read cache                     |
| Debezium Server   | 2.7     | CDC engine -- direct Redis Sink mode         |
| Debezium Connect  | 2.7     | CDC engine -- Kafka Connect mode             |
| Kafka (KRaft)     | 3.7     | Event streaming (Debezium Kafka mode)        |
| k3d               | 5.x     | Local Kubernetes cluster                     |
| Helm              | 3.x     | Kubernetes package manager                   |
| Guava             | 33.4    | CaseFormat for snake_case to camelCase       |
| Lombok            | 1.18.x  | Boilerplate reduction                        |
| springdoc-openapi | 3.0.2   | Swagger UI / OpenAPI 3                       |
| Maven Checkstyle  | 3.6.0   | Code style enforcement (Google-based)        |

---

## Prerequisites

- **Java 21+** -- [Download](https://adoptium.net/)
- **Maven 3.8+** -- [Download](https://maven.apache.org/download.cgi) (or use included `./mvnw`)
- **Docker** -- Rancher Desktop or Docker Desktop
- **k3d** v5.x -- `brew install k3d`
- **kubectl** -- `brew install kubectl`
- **Helm** 3.x -- `brew install helm`
- **redis-cli** -- `brew install redis` (for manual Redis inspection)

---

## Quick Start

### Debezium Redis Sink (direct CDC pipeline)

```bash
# 1. Set up infrastructure (k3d + Redis + PostgreSQL + Debezium Server)
make setup-debezium-redis-sink          # kubectl
make helm-setup-debezium-redis-sink     # OR Helm

# 2. Start port-forwards
make port-forward-all                   # kubectl
make helm-port-forward-all              # OR Helm

# 3. Start Spring Boot app
export DB_PASSWORD=postgres
./mvnw spring-boot:run -Dspring-boot.run.profiles=debezium-redis-sink

# OR run from IntelliJ with profile: debezium-redis-sink, env: DB_PASSWORD=postgres
```

### Debezium Kafka (production-grade CDC pipeline)

```bash
# 1. Set up infrastructure (k3d + Redis + PostgreSQL + Kafka + Debezium Connect)
make setup-debezium-kafka               # kubectl
make helm-setup-debezium-kafka          # OR Helm

# 2. Start port-forwards
make port-forward-all                   # kubectl
make helm-port-forward-all              # OR Helm

# 3. Start Spring Boot app
export DB_PASSWORD=postgres
./mvnw spring-boot:run -Dspring-boot.run.profiles=kafka

# OR run from IntelliJ with profile: kafka, env: DB_PASSWORD=postgres
```

### Verify

```bash
# Health check
curl http://localhost:8082/actuator/health

# CDC mode check
curl http://localhost:8082/api/health/cdc

# Swagger UI
open http://localhost:8082/swagger-ui.html

# Create a user (Write API --> PostgreSQL)
curl -X POST http://localhost:8082/api/users \
  -H "Content-Type: application/json" \
  -d '{"username":"alice","email":"alice@example.com","role":"ADMIN"}'

# Read user from Redis (Read API --> Redis, populated by CDC)
curl http://localhost:8082/api/users/1
```

### Cleanup

```bash
make port-forward-stop      # Stop port-forwards
make teardown               # kubectl: delete cluster
make helm-teardown          # Helm: uninstall release + delete cluster
```

---

## API Endpoints

### Write API (PostgreSQL)

| Method | Path                        | Description             |
|--------|-----------------------------|-------------------------|
| POST   | `/api/users`                | Create user             |
| PUT    | `/api/users/{id}`           | Update user             |
| DELETE | `/api/users/{id}`           | Delete user             |
| POST   | `/api/orders`               | Create order            |
| PUT    | `/api/orders/{id}`          | Update order            |
| DELETE | `/api/orders/{id}`          | Delete order            |
| PATCH  | `/api/orders/{id}/status`   | Update order status     |

### Read API (Redis)

| Method | Path                        | Description                    |
|--------|-----------------------------|--------------------------------|
| GET    | `/api/users`                | Get all users from Redis       |
| GET    | `/api/users/{id}`           | Get user by ID from Redis      |
| GET    | `/api/orders`               | Get all orders from Redis      |
| GET    | `/api/orders/{id}`          | Get order by ID from Redis     |
| GET    | `/api/orders/user/{userId}` | Get orders by user from Redis  |

### Health & Observability

| Method | Path                   | Description                    |
|--------|------------------------|--------------------------------|
| GET    | `/api/health/cdc`      | CDC pipeline status            |
| GET    | `/actuator/health`     | Spring Boot health             |
| GET    | `/actuator/info`       | Application info               |
| GET    | `/actuator/metrics`    | Available metrics              |

---

## Project Structure

```
Java-Posgtres-Debezium-Redis/
├── pom.xml                                     # Maven build config
├── checkstyle.xml                              # Code style (Google-based)
├── Makefile                                    # kubectl + Helm targets
├── make/
│   └── helm.mk                                # Helm-specific Makefile targets
├── k8s/                                        # Raw Kubernetes manifests
│   ├── redis/                                  #   Redis deployment + service
│   ├── postgres/                               #   PostgreSQL (custom image + WAL config)
│   ├── debezium-server/                        #   Debezium Server (Redis Sink mode)
│   └── kafka/                                  #   Kafka + Debezium Connect
│       └── debezium-connect/                   #     Connect deployment + connector JSON
├── helm/cdc-demo/                              # Helm umbrella chart
│   ├── Chart.yaml                              #   Dependencies on 5 subcharts
│   ├── values.yaml                             #   Defaults (all disabled)
│   ├── values-debezium-redis-sink.yaml         #   Redis Sink mode preset
│   ├── values-debezium-kafka.yaml              #   Kafka mode preset
│   └── charts/                                 #   Subcharts
│       ├── redis/
│       ├── postgres/
│       ├── debezium-server/
│       ├── kafka/
│       └── debezium-connect/
├── src/main/java/com/example/cdc/
│   ├── CdcDemoApplication.java                 # Entry point + startup banner
│   ├── config/
│   │   ├── RedisConfig.java                    #   RedisTemplate (StringRedisSerializer)
│   │   ├── JacksonConfig.java                  #   ObjectMapper bean
│   │   └── KafkaConfig.java                    #   Kafka consumer factory (kafka profile)
│   ├── controller/
│   │   ├── UserWriteController.java            #   POST/PUT/DELETE /api/users
│   │   ├── UserReadController.java             #   GET /api/users (from Redis)
│   │   ├── OrderWriteController.java           #   POST/PUT/PATCH/DELETE /api/orders
│   │   ├── OrderReadController.java            #   GET /api/orders (from Redis)
│   │   └── CdcHealthController.java            #   GET /api/health/cdc
│   ├── dto/                                    # Request/Response DTOs + ApiResponse wrapper
│   ├── exception/                              # GlobalExceptionHandler
│   ├── model/                                  # JPA entities (User, Order)
│   ├── repository/                             # Spring Data JPA repositories
│   ├── service/
│   │   ├── UserWriteService.java               #   User CRUD --> PostgreSQL
│   │   ├── UserReadService.java                #   User reads --> Redis
│   │   ├── OrderWriteService.java              #   Order CRUD --> PostgreSQL
│   │   └── OrderReadService.java               #   Order reads --> Redis
│   └── consumer/
│       ├── CdcEventTransformer.java            #   Shared: Debezium envelope --> Redis keys
│       ├── RedisStreamConsumer.java            #   debezium-redis-sink profile
│       └── KafkaCdcConsumer.java               #   kafka profile
├── src/main/resources/
│   ├── application.yaml                        # Common config
│   ├── application-debezium-redis-sink.yaml    # Redis Sink profile
│   └── application-kafka.yaml                  # Kafka profile
├── scripts/
│   ├── setup-debezium-redis-sink.sh            # Infra setup script (Redis Sink)
│   ├── setup-debezium-kafka.sh                 # Infra setup script (Kafka)
│   └── test-cdc-pipeline.sh                    # End-to-end test script
└── docs/
    ├── TESTING_GUIDE_DEBEZIUM_REDIS_SINK.md    # Step-by-step testing guide
    ├── TESTING_GUIDE_DEBEZIUM_KAFKA.md         # Step-by-step testing guide
    └── CDC_Demo.postman_collection.json        # Postman collection (27 requests)
```

---

## Makefile Targets

Run `make help` to see all available targets. Key targets:

### kubectl-based

| Target | Description |
|--------|-------------|
| `make setup-debezium-redis-sink` | Full setup: k3d + Redis + PostgreSQL + Debezium Server |
| `make setup-debezium-kafka` | Full setup: k3d + Redis + PostgreSQL + Kafka + Debezium Connect |
| `make port-forward-all` | Forward PostgreSQL(5432) and Redis(6379) to localhost |
| `make app-run-debezium-redis-sink` | Start app with Redis Sink profile |
| `make app-run-debezium-kafka` | Start app with Kafka profile |
| `make teardown` | Delete k3d cluster and all resources |
| `make status` | Show pods, services, PVCs |

### Helm-based

| Target | Description |
|--------|-------------|
| `make helm-setup-debezium-redis-sink` | Full Helm setup: k3d + Helm install (Redis Sink) |
| `make helm-setup-debezium-kafka` | Full Helm setup: k3d + Helm install (Kafka) |
| `make helm-port-forward-all` | Forward using Helm service names |
| `make helm-status` | Show Helm release status and resources |
| `make helm-upgrade VALUES=<file>` | Upgrade Helm release |
| `make helm-uninstall` | Uninstall Helm release (keep cluster) |
| `make helm-teardown` | Uninstall Helm + delete cluster |

### Common

| Target | Description |
|--------|-------------|
| `make app-stop` | Kill Spring Boot on port 8082 |
| `make app-build` | Maven compile + checkstyle |
| `make port-forward-stop` | Kill all port-forward processes |

---

## Testing

### Automated Test Script

The project includes an end-to-end test script that tests CREATE, READ, UPDATE, PATCH, DELETE operations and verifies CDC propagation:

```bash
# Run after app is started (works with both CDC modes)
./scripts/test-cdc-pipeline.sh
```

### Infrastructure Setup Scripts

Automate full infra setup (no app startup -- run app from IDE):

```bash
# Debezium Redis Sink
./scripts/setup-debezium-redis-sink.sh          # kubectl mode
./scripts/setup-debezium-redis-sink.sh --helm   # Helm mode

# Debezium Kafka
./scripts/setup-debezium-kafka.sh               # kubectl mode
./scripts/setup-debezium-kafka.sh --helm        # Helm mode
```

### Testing Guides

Detailed step-by-step testing documentation:

- **[Debezium Redis Sink Testing Guide](docs/TESTING_GUIDE_DEBEZIUM_REDIS_SINK.md)** -- Architecture flow, infra setup (kubectl/Helm), Spring Boot config (terminal + IntelliJ), data creation, PostgreSQL/Redis verification, CDC operations (UPDATE/DELETE), troubleshooting
- **[Debezium Kafka Testing Guide](docs/TESTING_GUIDE_DEBEZIUM_KAFKA.md)** -- Same structure plus Kafka topic inspection, connector management via REST API, Kafka-specific troubleshooting

### Postman Collection

A pre-built Postman collection with 27 requests across 7 folders:

**File:** [`docs/CDC_Demo.postman_collection.json`](docs/CDC_Demo.postman_collection.json)

**To import:**
1. Open Postman
2. Click **Import** (top left)
3. Drag the JSON file or click **Upload Files** and select it

**Collection variable:** `baseUrl` = `http://localhost:8082`

| Folder | Requests |
|--------|----------|
| User Write API | Create (x3), Update, Delete |
| Order Write API | Create (x3), Full Update, Status Update (x2), Delete |
| User Read API | Get All, Get by ID, 404 case |
| Order Read API | Get All, Get by ID, Get by User, 404 case |
| Health & Observability | CDC Health, Actuator Health/Info/Metrics |
| CDC End-to-End Flow | 6-step demo: Create --> Read --> Update --> Verify --> Delete --> Verify 404 |
| Validation Error Cases | Missing fields, invalid email, zero amount, not found, malformed JSON |

---

## Configuration

### Application Properties

| Property | Default | Description |
|----------|---------|-------------|
| `server.port` | `8082` | HTTP server port |
| `spring.data.redis.host` | `localhost` | Redis host |
| `spring.data.redis.port` | `6379` | Redis port |
| `spring.datasource.url` | `localhost:5432/cdc_demo` | PostgreSQL JDBC URL |
| `spring.datasource.password` | `${DB_PASSWORD}` | PostgreSQL password (env var) |
| `app.redis.key-prefix.user` | `user:` | Redis key prefix for users |
| `app.redis.key-prefix.order` | `order:` | Redis key prefix for orders |

### Spring Profiles

| Profile | CDC Mode | Consumer |
|---------|----------|----------|
| `debezium-redis-sink` | Debezium Server --> Redis Streams | `RedisStreamConsumer` |
| `kafka` | Debezium Connect --> Kafka | `KafkaCdcConsumer` |

---

## Code Style

- **Lombok** -- `@Data`, `@Builder`, `@Slf4j`, `@RequiredArgsConstructor`
- **Checkstyle** -- Google-based, runs at Maven `validate` phase
- **Rules** -- 150 char line length, 500 line file limit, 60 line method limit, no star imports, no tabs
- **Naming** -- `camelCase` for members/methods, `UPPER_SNAKE` for constants
- **Javadoc** -- ASCII diagrams on public classes/methods

```bash
# Run checkstyle
./mvnw checkstyle:check

# Build (includes checkstyle)
./mvnw clean compile
```

---

## Build Commands

```bash
# Build (includes checkstyle validation)
./mvnw clean compile

# Run with Redis Sink profile
./mvnw spring-boot:run -Dspring-boot.run.profiles=debezium-redis-sink

# Run with Kafka profile
./mvnw spring-boot:run -Dspring-boot.run.profiles=kafka

# Package as executable JAR
./mvnw package -DskipTests
```

---

## Redis Key Conventions

| Key Pattern  | Type   | Description |
|-------------|--------|-------------|
| `user:{id}` | String | JSON user object (CDC-populated) |
| `order:{id}` | String | JSON order object (CDC-populated) |

---

## License

This project is licensed under the MIT License.
