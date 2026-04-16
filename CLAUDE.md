# CLAUDE.md

This file provides guidance to Claude Code when working with code in this repository.

## Build & Run Commands

```bash
# Build (includes checkstyle validation phase)
./mvnw clean compile

# Run (Phase 1 — Debezium Server mode)
./mvnw spring-boot:run -Dspring-boot.run.profiles=debezium-redis-sink

# Run (Phase 2 — Kafka mode)
./mvnw spring-boot:run -Dspring-boot.run.profiles=kafka

# Checkstyle only
./mvnw checkstyle:check

# Package (skip tests)
./mvnw package -DskipTests
```

The app runs on port 8082. Requires Redis on localhost:6379 and PostgreSQL on localhost:5432 (k3d NodePort).

## Architecture

Spring Boot 4.0.5 / Java 21 application demonstrating CDC (Change Data Capture) with Debezium.
Writes go to PostgreSQL via REST API. Debezium streams WAL changes to Redis.
Reads are served from Redis (CDC-populated cache).

### Two CDC Modes (Spring Profiles)

**Phase 1 — Debezium Server (`debezium-redis-sink` profile):**
Debezium Server reads PostgreSQL WAL and writes CDC events to Redis Streams.
Spring Boot `RedisStreamConsumer` transforms events into `user:{id}` / `order:{id}` JSON keys.

**Phase 2 — Kafka (`kafka` profile):**
Debezium Kafka Connect reads PostgreSQL WAL and publishes to Kafka topics.
Spring Boot `@KafkaListener` consumes events and writes to Redis.

### Key Abstractions

- Write Controllers: `UserWriteController`, `OrderWriteController` — CRUD to PostgreSQL
- Read Controllers: `UserReadController`, `OrderReadController` — reads from Redis
- Write Services: `UserWriteService`, `OrderWriteService` — JPA persistence
- Read Services: `UserReadService`, `OrderReadService` — Redis JSON lookups
- CDC Consumers: `RedisStreamConsumer` (Phase 1), `KafkaCdcConsumer` (Phase 2)
- `CdcEventTransformer`: shared Debezium envelope parsing

### Redis Key Conventions

- Users: `user:{id}` — JSON string
- Orders: `order:{id}` — JSON string

## Code Style

- Uses Lombok heavily (@Data, @Builder, @RequiredArgsConstructor, @Slf4j)
- Checkstyle runs at Maven validate phase — config in checkstyle.xml (Google-based, relaxed for Lombok)
- Key checkstyle rules: 150 char line length, 500 line file limit, 60 line method limit, no star imports, no tabs
- camelCase for members/methods/params/locals, UPPER_SNAKE for constants
- All serialization is StringRedisSerializer
- Swagger/OpenAPI via springdoc (/swagger-ui.html)

## API Base Paths

- User Write: `/api/users` (POST, PUT, DELETE)
- User Read: `/api/users` and `/api/users/{id}` (GET)
- Order Write: `/api/orders` (POST, PUT, DELETE, PATCH)
- Order Read: `/api/orders`, `/api/orders/{id}`, `/api/orders/user/{userId}` (GET)
- CDC Health: `/api/health/cdc` (GET)
