# CDC Write-Back Redis Cache — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a Spring Boot microservice that writes to PostgreSQL and serves reads from Redis, with Debezium CDC streaming WAL changes to Redis in two phases (Debezium Server direct, then Kafka pipeline).

**Architecture:** Write API → PostgreSQL → Debezium reads WAL → writes CDC events to Redis Streams → Spring Boot stream consumer transforms events into `user:{id}` / `order:{id}` JSON keys in Redis → Read API serves from Redis. Phase 2 replaces Redis Streams with Kafka.

**Tech Stack:** Spring Boot 4.0.5, Java 21, PostgreSQL 16, Redis 6+, Debezium 2.7, Kafka (KRaft) 3.7, k3d, Lombok, springdoc-openapi 3.0.2

---

## File Structure

```
Java-Posgtres-Debezium-Redis/
├── pom.xml
├── checkstyle.xml
├── Makefile
├── CLAUDE.md
├── k8s/
│   ├── postgres/
│   │   ├── Dockerfile
│   │   ├── postgresql.conf
│   │   ├── deployment.yaml
│   │   └── service.yaml
│   ├── debezium-server/
│   │   ├── deployment.yaml
│   │   └── service.yaml
│   └── kafka/
│       ├── deployment.yaml
│       ├── service.yaml
│       └── debezium-connect/
│           ├── deployment.yaml
│           ├── service.yaml
│           └── register-connector.json
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
│   │   │   └── EntityNotFoundException.java
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
└── src/test/java/com/example/cdc/
    └── CdcDemoApplicationTests.java
```

---

### Task 1: Project Scaffolding — Maven, Checkstyle, Application Entry Point

**Files:**
- Create: `pom.xml`
- Create: `checkstyle.xml`
- Create: `src/main/java/com/example/cdc/CdcDemoApplication.java`
- Create: `src/main/resources/application.yaml`
- Create: `src/main/resources/application-debezium-server.yaml`
- Create: `src/main/resources/application-kafka.yaml`
- Create: `src/main/resources/init.sql`
- Create: `.gitignore`
- Create: `CLAUDE.md`

- [ ] **Step 1: Create `.gitignore`**

```gitignore
target/
!.mvn/wrapper/maven-wrapper.jar
!**/src/main/**/target/
!**/src/test/**/target/

### IntelliJ IDEA ###
.idea
*.iws
*.iml
*.ipr

### Eclipse ###
.apt_generated
.classpath
.factorypath
.project
.settings
.springBeans
.sts4-cache

### VS Code ###
.vscode/

### OS ###
.DS_Store

### Logs ###
logs/
*.log
```

- [ ] **Step 2: Create `pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>4.0.5</version>
        <relativePath/>
    </parent>

    <groupId>com.example</groupId>
    <artifactId>cdc-demo</artifactId>
    <version>0.0.1-SNAPSHOT</version>
    <name>CDC Demo - Postgres Debezium Redis</name>
    <description>Spring Boot project demonstrating CDC with Debezium streaming PostgreSQL WAL to Redis</description>

    <properties>
        <java.version>21</java.version>
    </properties>

    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-jpa</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-redis</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.kafka</groupId>
            <artifactId>spring-kafka</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springdoc</groupId>
            <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
            <version>3.0.2</version>
        </dependency>
        <dependency>
            <groupId>org.postgresql</groupId>
            <artifactId>postgresql</artifactId>
            <scope>runtime</scope>
        </dependency>
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <optional>true</optional>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <configuration>
                    <annotationProcessorPaths>
                        <path>
                            <groupId>org.projectlombok</groupId>
                            <artifactId>lombok</artifactId>
                            <version>${lombok.version}</version>
                        </path>
                    </annotationProcessorPaths>
                </configuration>
            </plugin>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-checkstyle-plugin</artifactId>
                <version>3.6.0</version>
                <dependencies>
                    <dependency>
                        <groupId>com.puppycrawl.tools</groupId>
                        <artifactId>checkstyle</artifactId>
                        <version>10.21.4</version>
                    </dependency>
                </dependencies>
                <configuration>
                    <configLocation>checkstyle.xml</configLocation>
                    <consoleOutput>true</consoleOutput>
                    <failsOnError>true</failsOnError>
                    <violationSeverity>warning</violationSeverity>
                    <includeTestSourceDirectory>false</includeTestSourceDirectory>
                </configuration>
                <executions>
                    <execution>
                        <id>validate</id>
                        <phase>validate</phase>
                        <goals>
                            <goal>check</goal>
                        </goals>
                    </execution>
                </executions>
            </plugin>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
                <configuration>
                    <excludes>
                        <exclude>
                            <groupId>org.projectlombok</groupId>
                            <artifactId>lombok</artifactId>
                        </exclude>
                    </excludes>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 3: Copy `checkstyle.xml` from Java_Redis project**

Copy the exact `checkstyle.xml` from `/Users/viskumar/Java_Workspace/Java_Redis/checkstyle.xml` — same Google-based rules with Lombok relaxations.

- [ ] **Step 4: Create `application.yaml`**

```yaml
spring:
  application:
    name: cdc-demo
  data:
    redis:
      host: localhost
      port: 6379
      timeout: 2000ms
      lettuce:
        pool:
          max-active: 8
          max-idle: 8
          min-idle: 2
          max-wait: -1ms
  datasource:
    url: jdbc:postgresql://localhost:5432/cdc_demo
    username: postgres
    password: ${DB_PASSWORD}
    hikari:
      maximum-pool-size: 10
      minimum-idle: 2
  jpa:
    hibernate:
      ddl-auto: update
    show-sql: false
    properties:
      hibernate:
        dialect: org.hibernate.dialect.PostgreSQLDialect
  sql:
    init:
      mode: always
      schema-locations: classpath:init.sql
  kafka:
    consumer:
      auto-offset-reset: earliest
      group-id: cdc-demo-group
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.apache.kafka.common.serialization.StringDeserializer
    bootstrap-servers: localhost:9092

server:
  port: 8082

management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics
  endpoint:
    health:
      show-details: always

app:
  redis:
    key-prefix:
      user: "user:"
      order: "order:"
```

- [ ] **Step 5: Create `application-debezium-server.yaml`**

```yaml
app:
  cdc:
    mode: debezium-server
    redis-stream:
      users-stream: "cdc_demo.public.users"
      orders-stream: "cdc_demo.public.orders"
      consumer-group: "cdc-consumer-group"
      consumer-name: "cdc-consumer-1"

spring:
  autoconfigure:
    exclude:
      - org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration
```

- [ ] **Step 6: Create `application-kafka.yaml`**

```yaml
app:
  cdc:
    mode: kafka
    kafka:
      users-topic: "cdc_demo.public.users"
      orders-topic: "cdc_demo.public.orders"
```

- [ ] **Step 7: Create `init.sql`**

```sql
-- Ensure PostgreSQL has logical replication support for Debezium
-- Tables are created by Hibernate ddl-auto, this handles any extra setup
ALTER SYSTEM SET wal_level = 'logical';
```

Note: This `init.sql` is a safety net. The custom Postgres Docker image already has `wal_level=logical`. Spring Boot `spring.sql.init` runs this on startup. If the ALTER fails (e.g., on Aiven Cloud), it's non-fatal.

- [ ] **Step 8: Create `CdcDemoApplication.java`**

```java
package com.example.cdc;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * <b>CDC Demo Application</b>
 *
 * <p>Spring Boot entry point for the CDC (Change Data Capture) demo. Demonstrates
 * streaming PostgreSQL WAL changes to Redis via Debezium, with writes going to
 * PostgreSQL and reads served from Redis.
 *
 * <pre>
 *  Client ──> Write API ──> PostgreSQL ──> Debezium ──> Redis
 *             Read API  <──────────────────────────────┘
 * </pre>
 */
@SpringBootApplication
public class CdcDemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(CdcDemoApplication.class, args);
    }

    /**
     * <b>Startup Logger</b>
     *
     * <p>Prints a formatted banner after application context initialization listing the
     * active port, CDC mode, Swagger UI URL, and all available REST API paths.
     */
    @Slf4j
    @Component
    static class StartupLogger implements ApplicationRunner {

        private final Environment env;

        StartupLogger(Environment env) {
            this.env = env;
        }

        @Override
        public void run(ApplicationArguments args) {
            String port = env.getProperty("server.port", "8082");
            String cdcMode = env.getProperty("app.cdc.mode", "none");
            log.info("""
                    ==========================================================
                      CDC Demo App is READY
                      Port:        {}
                      CDC Mode:    {}
                      Swagger UI:  http://localhost:{}/swagger-ui.html
                      Actuator:    http://localhost:{}/actuator/health
                      --------------------------------------------------
                      Write API (PostgreSQL):
                        Users:   POST|PUT|DELETE /api/users
                        Orders:  POST|PUT|DELETE /api/orders
                        Status:  PATCH /api/orders/{{id}}/status
                      --------------------------------------------------
                      Read API (Redis):
                        Users:   GET /api/users, /api/users/{{id}}
                        Orders:  GET /api/orders, /api/orders/{{id}}
                        By User: GET /api/orders/user/{{userId}}
                      --------------------------------------------------
                      Health:  GET /api/health/cdc
                    ==========================================================""",
                    port, cdcMode, port, port);
        }
    }
}
```

- [ ] **Step 9: Create `CLAUDE.md`**

```markdown
# CLAUDE.md

This file provides guidance to Claude Code when working with code in this repository.

## Build & Run Commands

` ` `bash
# Build (includes checkstyle validation phase)
./mvnw clean compile

# Run (Phase 1 — Debezium Server mode)
./mvnw spring-boot:run -Dspring-boot.run.profiles=debezium-server

# Run (Phase 2 — Kafka mode)
./mvnw spring-boot:run -Dspring-boot.run.profiles=kafka

# Checkstyle only
./mvnw checkstyle:check

# Package (skip tests)
./mvnw package -DskipTests
` ` `

The app runs on port 8082. Requires Redis on localhost:6379 and PostgreSQL on localhost:5432 (k3d NodePort).

## Architecture

Spring Boot 4.0.5 / Java 21 application demonstrating CDC (Change Data Capture) with Debezium.
Writes go to PostgreSQL via REST API. Debezium streams WAL changes to Redis.
Reads are served from Redis (CDC-populated cache).

### Two CDC Modes (Spring Profiles)

**Phase 1 — Debezium Server (`debezium-server` profile):**
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
```

- [ ] **Step 10: Initialize git and commit**

```bash
cd /Users/viskumar/Java_Workspace/Java-Posgtres-Debezium-Redis
git init
git add .
git commit -m "feat: project scaffolding — pom.xml, checkstyle, application configs, entry point"
```

---

### Task 2: Domain Models — JPA Entities

**Files:**
- Create: `src/main/java/com/example/cdc/model/User.java`
- Create: `src/main/java/com/example/cdc/model/Order.java`

- [ ] **Step 1: Create `User.java`**

```java
package com.example.cdc.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * <b>User JPA Entity</b>
 *
 * <p>Persistent representation of a user in the {@code users} table. Changes to this table
 * are captured by Debezium CDC and streamed to Redis as JSON strings keyed {@code user:{id}}.
 *
 * <pre>
 *  Table: users
 *  ┌────┬──────────┬─────────────────┬─────────┬────────────┬────────────┐
 *  │ id │ username │ email           │ role    │ created_at │ updated_at │
 *  ├────┼──────────┼─────────────────┼─────────┼────────────┼────────────┤
 *  │  1 │ alice    │ alice@email.com │ USER    │ ...        │ ...        │
 *  │  2 │ bob      │ bob@email.com   │ ADMIN   │ ...        │ ...        │
 *  └────┴──────────┴─────────────────┴─────────┴────────────┴────────────┘
 * </pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String username;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(nullable = false)
    private String role;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
```

- [ ] **Step 2: Create `Order.java`**

```java
package com.example.cdc.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * <b>Order JPA Entity</b>
 *
 * <p>Persistent representation of an order in the {@code orders} table. References a
 * {@link User} by {@code userId}. Changes are captured by Debezium CDC and streamed
 * to Redis as JSON strings keyed {@code order:{id}}.
 *
 * <pre>
 *  Table: orders
 *  ┌────┬─────────┬────────┬───────────┬─────────────┬────────────┬────────────┐
 *  │ id │ user_id │ amount │ status    │ description │ created_at │ updated_at │
 *  ├────┼─────────┼────────┼───────────┼─────────────┼────────────┼────────────┤
 *  │  1 │       1 │  99.99 │ CONFIRMED │ Laptop      │ ...        │ ...        │
 *  │  2 │       1 │  29.50 │ PENDING   │ Mouse       │ ...        │ ...        │
 *  └────┴─────────┴────────┴───────────┴─────────────┴────────────┴────────────┘
 * </pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "orders")
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false)
    private String status;

    private String description;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
```

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/example/cdc/model/
git commit -m "feat: add User and Order JPA entities"
```

---

### Task 3: DTOs and API Response Wrapper

**Files:**
- Create: `src/main/java/com/example/cdc/dto/ApiResponse.java`
- Create: `src/main/java/com/example/cdc/dto/UserRequest.java`
- Create: `src/main/java/com/example/cdc/dto/UserResponse.java`
- Create: `src/main/java/com/example/cdc/dto/OrderRequest.java`
- Create: `src/main/java/com/example/cdc/dto/OrderResponse.java`
- Create: `src/main/java/com/example/cdc/dto/OrderStatusRequest.java`

- [ ] **Step 1: Create `ApiResponse.java`**

Copy the exact `ApiResponse.java` from Java_Redis — same class with `ok(data)`, `ok(message, data)`, `error(message)` factory methods. Change package to `com.example.cdc.dto`.

- [ ] **Step 2: Create `UserRequest.java`**

```java
package com.example.cdc.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * <b>User Request DTO</b>
 *
 * <p>Request body for creating and updating users via the Write API.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserRequest {

    @NotBlank(message = "username is required")
    private String username;

    @NotBlank(message = "email is required")
    @Email(message = "email must be valid")
    private String email;

    @NotBlank(message = "role is required")
    private String role;
}
```

- [ ] **Step 3: Create `UserResponse.java`**

```java
package com.example.cdc.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * <b>User Response DTO</b>
 *
 * <p>Response body returned by both Write API (from PostgreSQL) and Read API (from Redis).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserResponse {

    private Long id;
    private String username;
    private String email;
    private String role;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
```

- [ ] **Step 4: Create `OrderRequest.java`**

```java
package com.example.cdc.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * <b>Order Request DTO</b>
 *
 * <p>Request body for creating and updating orders via the Write API.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderRequest {

    @NotNull(message = "userId is required")
    private Long userId;

    @NotNull(message = "amount is required")
    @DecimalMin(value = "0.01", message = "amount must be greater than 0")
    private BigDecimal amount;

    private String status;

    private String description;
}
```

- [ ] **Step 5: Create `OrderResponse.java`**

```java
package com.example.cdc.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * <b>Order Response DTO</b>
 *
 * <p>Response body returned by both Write API (from PostgreSQL) and Read API (from Redis).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderResponse {

    private Long id;
    private Long userId;
    private BigDecimal amount;
    private String status;
    private String description;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
```

- [ ] **Step 6: Create `OrderStatusRequest.java`**

```java
package com.example.cdc.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * <b>Order Status Request DTO</b>
 *
 * <p>Request body for updating only the status of an existing order.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderStatusRequest {

    @NotBlank(message = "status is required")
    private String status;
}
```

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/example/cdc/dto/
git commit -m "feat: add DTOs — ApiResponse, UserRequest/Response, OrderRequest/Response, OrderStatusRequest"
```

---

### Task 4: Exception Handling

**Files:**
- Create: `src/main/java/com/example/cdc/exception/EntityNotFoundException.java`
- Create: `src/main/java/com/example/cdc/exception/GlobalExceptionHandler.java`

- [ ] **Step 1: Create `EntityNotFoundException.java`**

```java
package com.example.cdc.exception;

/**
 * <b>Entity Not Found Exception</b>
 *
 * <p>Thrown when a requested entity does not exist in PostgreSQL (write operations)
 * or Redis (read operations).
 */
public class EntityNotFoundException extends RuntimeException {

    public EntityNotFoundException(String entityType, Long id) {
        super("%s with id %d not found".formatted(entityType, id));
    }

    public EntityNotFoundException(String message) {
        super(message);
    }
}
```

- [ ] **Step 2: Create `GlobalExceptionHandler.java`**

Copy the structure from Java_Redis's `GlobalExceptionHandler.java`. Change package to `com.example.cdc.exception`. Replace `MemberNotFoundException` handler with `EntityNotFoundException` handler. Keep all other handlers (validation, malformed body, method not supported, missing param, type mismatch, catch-all).

```java
package com.example.cdc.exception;

import com.example.cdc.dto.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.stream.Collectors;

/**
 * <b>Global Exception Handler</b>
 *
 * <p>Centralized {@link RestControllerAdvice} that intercepts all exceptions thrown by REST
 * controllers and translates them into uniform {@link ApiResponse} error payloads.
 *
 * <pre>
 *  ┌────────────┐    ┌───────────────────────────┐    ┌──────────────┐
 *  │ Controller │──X─│ GlobalExceptionHandler     │───>│ ApiResponse  │
 *  │  throws    │    │ catches + maps to HTTP code│    │ error JSON   │
 *  └────────────┘    └───────────────────────────┘    └──────────────┘
 * </pre>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleEntityNotFound(EntityNotFoundException ex) {
        log.warn("Entity not found: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error(ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException ex) {
        String errors = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .collect(Collectors.joining(", "));
        log.warn("Validation failed: {}", errors);
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(errors));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnreadable(HttpMessageNotReadableException ex) {
        log.warn("Malformed request body: {}", ex.getMessage());
        return ResponseEntity.badRequest()
                .body(ApiResponse.error("Invalid request body format"));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException ex) {
        log.warn("Method not supported: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(ApiResponse.error(
                        "HTTP method '%s' is not supported for this endpoint".formatted(ex.getMethod())));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingParam(
            MissingServletRequestParameterException ex) {
        log.warn("Missing parameter: {}", ex.getMessage());
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(
                        "Required parameter '%s' is missing".formatted(ex.getParameterName())));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(
            MethodArgumentTypeMismatchException ex) {
        log.warn("Type mismatch: {}", ex.getMessage());
        return ResponseEntity.badRequest()
                .body(ApiResponse.error("Parameter '%s' must be of type %s".formatted(
                        ex.getName(),
                        ex.getRequiredType() != null
                                ? ex.getRequiredType().getSimpleName() : "unknown")));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGeneral(Exception ex) {
        log.error("Unexpected error", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error("An unexpected error occurred"));
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/example/cdc/exception/
git commit -m "feat: add EntityNotFoundException and GlobalExceptionHandler"
```

---

### Task 5: Redis Config and JPA Repositories

**Files:**
- Create: `src/main/java/com/example/cdc/config/RedisConfig.java`
- Create: `src/main/java/com/example/cdc/repository/UserRepository.java`
- Create: `src/main/java/com/example/cdc/repository/OrderRepository.java`

- [ ] **Step 1: Create `RedisConfig.java`**

Copy from Java_Redis — same `RedisTemplate<String, String>` with `StringRedisSerializer` on all four channels. Change package to `com.example.cdc.config`.

- [ ] **Step 2: Create `UserRepository.java`**

```java
package com.example.cdc.repository;

import com.example.cdc.model.User;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * <b>User Repository</b>
 *
 * <p>Spring Data JPA repository for {@link User} CRUD operations.
 */
public interface UserRepository extends JpaRepository<User, Long> {
}
```

- [ ] **Step 3: Create `OrderRepository.java`**

```java
package com.example.cdc.repository;

import com.example.cdc.model.Order;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * <b>Order Repository</b>
 *
 * <p>Spring Data JPA repository for {@link Order} CRUD operations.
 */
public interface OrderRepository extends JpaRepository<Order, Long> {

    List<Order> findByUserId(Long userId);
}
```

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/example/cdc/config/ src/main/java/com/example/cdc/repository/
git commit -m "feat: add RedisConfig and JPA repositories"
```

---

### Task 6: Write Services — PostgreSQL CRUD

**Files:**
- Create: `src/main/java/com/example/cdc/service/UserWriteService.java`
- Create: `src/main/java/com/example/cdc/service/OrderWriteService.java`

- [ ] **Step 1: Create `UserWriteService.java`**

```java
package com.example.cdc.service;

import com.example.cdc.dto.UserRequest;
import com.example.cdc.dto.UserResponse;
import com.example.cdc.exception.EntityNotFoundException;
import com.example.cdc.model.User;
import com.example.cdc.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * <b>User Write Service</b>
 *
 * <p>Handles create, update, and delete operations for {@link User} entities in PostgreSQL.
 * Changes written here are captured by Debezium CDC and streamed to Redis.
 *
 * <pre>
 *  Controller ──> UserWriteService ──> UserRepository ──> PostgreSQL
 *                                                              │
 *                                                    Debezium reads WAL
 * </pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserWriteService {

    private final UserRepository userRepository;

    @Transactional
    public UserResponse create(UserRequest request) {
        User user = User.builder()
                .username(request.getUsername())
                .email(request.getEmail())
                .role(request.getRole())
                .build();
        User saved = userRepository.save(user);
        log.info("Created user id={} username={}", saved.getId(), saved.getUsername());
        return toResponse(saved);
    }

    @Transactional
    public UserResponse update(Long id, UserRequest request) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("User", id));
        user.setUsername(request.getUsername());
        user.setEmail(request.getEmail());
        user.setRole(request.getRole());
        User saved = userRepository.save(user);
        log.info("Updated user id={}", saved.getId());
        return toResponse(saved);
    }

    @Transactional
    public void delete(Long id) {
        if (!userRepository.existsById(id)) {
            throw new EntityNotFoundException("User", id);
        }
        userRepository.deleteById(id);
        log.info("Deleted user id={}", id);
    }

    private UserResponse toResponse(User user) {
        return UserResponse.builder()
                .id(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .role(user.getRole())
                .createdAt(user.getCreatedAt())
                .updatedAt(user.getUpdatedAt())
                .build();
    }
}
```

- [ ] **Step 2: Create `OrderWriteService.java`**

```java
package com.example.cdc.service;

import com.example.cdc.dto.OrderRequest;
import com.example.cdc.dto.OrderResponse;
import com.example.cdc.dto.OrderStatusRequest;
import com.example.cdc.exception.EntityNotFoundException;
import com.example.cdc.model.Order;
import com.example.cdc.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * <b>Order Write Service</b>
 *
 * <p>Handles create, update, delete, and status-change operations for {@link Order} entities
 * in PostgreSQL. Changes are captured by Debezium CDC and streamed to Redis.
 *
 * <pre>
 *  Controller ──> OrderWriteService ──> OrderRepository ──> PostgreSQL
 *                                                                │
 *                                                      Debezium reads WAL
 * </pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderWriteService {

    private static final String DEFAULT_STATUS = "PENDING";
    private final OrderRepository orderRepository;

    @Transactional
    public OrderResponse create(OrderRequest request) {
        Order order = Order.builder()
                .userId(request.getUserId())
                .amount(request.getAmount())
                .status(request.getStatus() != null ? request.getStatus() : DEFAULT_STATUS)
                .description(request.getDescription())
                .build();
        Order saved = orderRepository.save(order);
        log.info("Created order id={} userId={}", saved.getId(), saved.getUserId());
        return toResponse(saved);
    }

    @Transactional
    public OrderResponse update(Long id, OrderRequest request) {
        Order order = orderRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Order", id));
        order.setUserId(request.getUserId());
        order.setAmount(request.getAmount());
        order.setStatus(request.getStatus() != null ? request.getStatus() : order.getStatus());
        order.setDescription(request.getDescription());
        Order saved = orderRepository.save(order);
        log.info("Updated order id={}", saved.getId());
        return toResponse(saved);
    }

    @Transactional
    public OrderResponse updateStatus(Long id, OrderStatusRequest request) {
        Order order = orderRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Order", id));
        order.setStatus(request.getStatus());
        Order saved = orderRepository.save(order);
        log.info("Updated order id={} status={}", saved.getId(), saved.getStatus());
        return toResponse(saved);
    }

    @Transactional
    public void delete(Long id) {
        if (!orderRepository.existsById(id)) {
            throw new EntityNotFoundException("Order", id);
        }
        orderRepository.deleteById(id);
        log.info("Deleted order id={}", id);
    }

    private OrderResponse toResponse(Order order) {
        return OrderResponse.builder()
                .id(order.getId())
                .userId(order.getUserId())
                .amount(order.getAmount())
                .status(order.getStatus())
                .description(order.getDescription())
                .createdAt(order.getCreatedAt())
                .updatedAt(order.getUpdatedAt())
                .build();
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/example/cdc/service/
git commit -m "feat: add UserWriteService and OrderWriteService for PostgreSQL CRUD"
```

---

### Task 7: Read Services — Redis Lookups

**Files:**
- Create: `src/main/java/com/example/cdc/service/UserReadService.java`
- Create: `src/main/java/com/example/cdc/service/OrderReadService.java`

- [ ] **Step 1: Create `UserReadService.java`**

```java
package com.example.cdc.service;

import com.example.cdc.dto.UserResponse;
import com.example.cdc.exception.EntityNotFoundException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * <b>User Read Service</b>
 *
 * <p>Reads user data from Redis where it was populated by the Debezium CDC pipeline.
 * Keys follow the pattern {@code user:{id}} with JSON string values.
 *
 * <pre>
 *  Controller ──> UserReadService ──> Redis
 *                                     │
 *                          user:1 = {"id":1,"username":"alice",...}
 *                          user:2 = {"id":2,"username":"bob",...}
 * </pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserReadService {

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    @Value("${app.redis.key-prefix.user}")
    private String userKeyPrefix;

    public UserResponse getById(Long id) {
        String key = userKeyPrefix + id;
        String json = redisTemplate.opsForValue().get(key);
        if (json == null) {
            throw new EntityNotFoundException("User", id);
        }
        log.debug("Redis HIT key={}", key);
        return deserialize(json);
    }

    public List<UserResponse> getAll() {
        Set<String> keys = redisTemplate.keys(userKeyPrefix + "*");
        if (keys == null || keys.isEmpty()) {
            return List.of();
        }
        List<String> values = redisTemplate.opsForValue().multiGet(new ArrayList<>(keys));
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .filter(v -> v != null)
                .map(this::deserialize)
                .toList();
    }

    private UserResponse deserialize(String json) {
        try {
            return objectMapper.readValue(json, UserResponse.class);
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize user JSON: {}", json, e);
            throw new RuntimeException("Failed to deserialize user from Redis", e);
        }
    }
}
```

- [ ] **Step 2: Create `OrderReadService.java`**

```java
package com.example.cdc.service;

import com.example.cdc.dto.OrderResponse;
import com.example.cdc.exception.EntityNotFoundException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * <b>Order Read Service</b>
 *
 * <p>Reads order data from Redis where it was populated by the Debezium CDC pipeline.
 * Keys follow the pattern {@code order:{id}} with JSON string values.
 *
 * <pre>
 *  Controller ──> OrderReadService ──> Redis
 *                                      │
 *                           order:1 = {"id":1,"userId":1,"amount":99.99,...}
 *                           order:2 = {"id":2,"userId":1,"amount":29.50,...}
 * </pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderReadService {

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    @Value("${app.redis.key-prefix.order}")
    private String orderKeyPrefix;

    public OrderResponse getById(Long id) {
        String key = orderKeyPrefix + id;
        String json = redisTemplate.opsForValue().get(key);
        if (json == null) {
            throw new EntityNotFoundException("Order", id);
        }
        log.debug("Redis HIT key={}", key);
        return deserialize(json);
    }

    public List<OrderResponse> getAll() {
        Set<String> keys = redisTemplate.keys(orderKeyPrefix + "*");
        if (keys == null || keys.isEmpty()) {
            return List.of();
        }
        List<String> values = redisTemplate.opsForValue().multiGet(new ArrayList<>(keys));
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .filter(v -> v != null)
                .map(this::deserialize)
                .toList();
    }

    public List<OrderResponse> getByUserId(Long userId) {
        return getAll().stream()
                .filter(order -> userId.equals(order.getUserId()))
                .toList();
    }

    private OrderResponse deserialize(String json) {
        try {
            return objectMapper.readValue(json, OrderResponse.class);
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize order JSON: {}", json, e);
            throw new RuntimeException("Failed to deserialize order from Redis", e);
        }
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/example/cdc/service/
git commit -m "feat: add UserReadService and OrderReadService for Redis lookups"
```

---

### Task 8: Write Controllers

**Files:**
- Create: `src/main/java/com/example/cdc/controller/UserWriteController.java`
- Create: `src/main/java/com/example/cdc/controller/OrderWriteController.java`

- [ ] **Step 1: Create `UserWriteController.java`**

```java
package com.example.cdc.controller;

import com.example.cdc.dto.ApiResponse;
import com.example.cdc.dto.UserRequest;
import com.example.cdc.dto.UserResponse;
import com.example.cdc.service.UserWriteService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * <b>User Write Controller</b>
 *
 * <p>REST endpoints for creating, updating, and deleting users in PostgreSQL.
 * Changes are captured by Debezium CDC and automatically streamed to Redis.
 *
 * <pre>
 *  POST   /api/users        ──> Create user in PostgreSQL
 *  PUT    /api/users/{id}   ──> Update user in PostgreSQL
 *  DELETE /api/users/{id}   ──> Delete user from PostgreSQL
 * </pre>
 */
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
@Tag(name = "User Write API", description = "CRUD operations writing to PostgreSQL")
public class UserWriteController {

    private final UserWriteService userWriteService;

    @Operation(summary = "Create a new user")
    @PostMapping
    public ResponseEntity<ApiResponse<UserResponse>> create(
            @Valid @RequestBody UserRequest request) {
        UserResponse user = userWriteService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("User created", user));
    }

    @Operation(summary = "Update an existing user")
    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<UserResponse>> update(
            @PathVariable Long id,
            @Valid @RequestBody UserRequest request) {
        UserResponse user = userWriteService.update(id, request);
        return ResponseEntity.ok(ApiResponse.ok("User updated", user));
    }

    @Operation(summary = "Delete a user")
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        userWriteService.delete(id);
        return ResponseEntity.ok(ApiResponse.ok("User deleted", null));
    }
}
```

- [ ] **Step 2: Create `OrderWriteController.java`**

```java
package com.example.cdc.controller;

import com.example.cdc.dto.ApiResponse;
import com.example.cdc.dto.OrderRequest;
import com.example.cdc.dto.OrderResponse;
import com.example.cdc.dto.OrderStatusRequest;
import com.example.cdc.service.OrderWriteService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * <b>Order Write Controller</b>
 *
 * <p>REST endpoints for creating, updating, deleting, and changing status of orders
 * in PostgreSQL. Changes are captured by Debezium CDC and streamed to Redis.
 *
 * <pre>
 *  POST   /api/orders              ──> Create order
 *  PUT    /api/orders/{id}         ──> Update order
 *  DELETE /api/orders/{id}         ──> Delete order
 *  PATCH  /api/orders/{id}/status  ──> Update order status only
 * </pre>
 */
@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
@Tag(name = "Order Write API", description = "CRUD operations writing to PostgreSQL")
public class OrderWriteController {

    private final OrderWriteService orderWriteService;

    @Operation(summary = "Create a new order")
    @PostMapping
    public ResponseEntity<ApiResponse<OrderResponse>> create(
            @Valid @RequestBody OrderRequest request) {
        OrderResponse order = orderWriteService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Order created", order));
    }

    @Operation(summary = "Update an existing order")
    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<OrderResponse>> update(
            @PathVariable Long id,
            @Valid @RequestBody OrderRequest request) {
        OrderResponse order = orderWriteService.update(id, request);
        return ResponseEntity.ok(ApiResponse.ok("Order updated", order));
    }

    @Operation(summary = "Update order status only")
    @PatchMapping("/{id}/status")
    public ResponseEntity<ApiResponse<OrderResponse>> updateStatus(
            @PathVariable Long id,
            @Valid @RequestBody OrderStatusRequest request) {
        OrderResponse order = orderWriteService.updateStatus(id, request);
        return ResponseEntity.ok(ApiResponse.ok("Order status updated", order));
    }

    @Operation(summary = "Delete an order")
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        orderWriteService.delete(id);
        return ResponseEntity.ok(ApiResponse.ok("Order deleted", null));
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/example/cdc/controller/
git commit -m "feat: add UserWriteController and OrderWriteController"
```

---

### Task 9: Read Controllers

**Files:**
- Create: `src/main/java/com/example/cdc/controller/UserReadController.java`
- Create: `src/main/java/com/example/cdc/controller/OrderReadController.java`

- [ ] **Step 1: Create `UserReadController.java`**

```java
package com.example.cdc.controller;

import com.example.cdc.dto.ApiResponse;
import com.example.cdc.dto.UserResponse;
import com.example.cdc.service.UserReadService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * <b>User Read Controller</b>
 *
 * <p>REST endpoints for reading user data from Redis (CDC-populated cache).
 * Data is written to Redis by the Debezium CDC pipeline, not by this application directly.
 *
 * <pre>
 *  GET /api/users       ──> List all users from Redis
 *  GET /api/users/{id}  ──> Get single user from Redis
 * </pre>
 */
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
@Tag(name = "User Read API", description = "Read operations from Redis (CDC-populated)")
public class UserReadController {

    private final UserReadService userReadService;

    @Operation(summary = "Get all users from Redis")
    @GetMapping
    public ResponseEntity<ApiResponse<List<UserResponse>>> getAll() {
        List<UserResponse> users = userReadService.getAll();
        return ResponseEntity.ok(ApiResponse.ok(users));
    }

    @Operation(summary = "Get a user by ID from Redis")
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<UserResponse>> getById(@PathVariable Long id) {
        UserResponse user = userReadService.getById(id);
        return ResponseEntity.ok(ApiResponse.ok(user));
    }
}
```

- [ ] **Step 2: Create `OrderReadController.java`**

```java
package com.example.cdc.controller;

import com.example.cdc.dto.ApiResponse;
import com.example.cdc.dto.OrderResponse;
import com.example.cdc.service.OrderReadService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * <b>Order Read Controller</b>
 *
 * <p>REST endpoints for reading order data from Redis (CDC-populated cache).
 *
 * <pre>
 *  GET /api/orders              ──> List all orders from Redis
 *  GET /api/orders/{id}         ──> Get single order from Redis
 *  GET /api/orders/user/{userId}──> Get orders by user from Redis
 * </pre>
 */
@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
@Tag(name = "Order Read API", description = "Read operations from Redis (CDC-populated)")
public class OrderReadController {

    private final OrderReadService orderReadService;

    @Operation(summary = "Get all orders from Redis")
    @GetMapping
    public ResponseEntity<ApiResponse<List<OrderResponse>>> getAll() {
        List<OrderResponse> orders = orderReadService.getAll();
        return ResponseEntity.ok(ApiResponse.ok(orders));
    }

    @Operation(summary = "Get an order by ID from Redis")
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<OrderResponse>> getById(@PathVariable Long id) {
        OrderResponse order = orderReadService.getById(id);
        return ResponseEntity.ok(ApiResponse.ok(order));
    }

    @Operation(summary = "Get all orders for a user from Redis")
    @GetMapping("/user/{userId}")
    public ResponseEntity<ApiResponse<List<OrderResponse>>> getByUserId(
            @PathVariable Long userId) {
        List<OrderResponse> orders = orderReadService.getByUserId(userId);
        return ResponseEntity.ok(ApiResponse.ok(orders));
    }
}
```

- [ ] **Step 3: Build and verify compilation**

```bash
cd /Users/viskumar/Java_Workspace/Java-Posgtres-Debezium-Redis
./mvnw clean compile
```

Expected: `BUILD SUCCESS`

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/example/cdc/controller/
git commit -m "feat: add read controllers — UserReadController, OrderReadController"
```

---

### Task 10: CDC Event Transformer (Shared)

**Files:**
- Create: `src/main/java/com/example/cdc/consumer/CdcEventTransformer.java`

- [ ] **Step 1: Create `CdcEventTransformer.java`**

```java
package com.example.cdc.consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

/**
 * <b>CDC Event Transformer</b>
 *
 * <p>Shared component that parses Debezium CDC event envelopes and writes the transformed
 * data to Redis. Used by both {@link RedisStreamConsumer} (Phase 1) and
 * {@link KafkaCdcConsumer} (Phase 2).
 *
 * <pre>
 *  Debezium CDC Event Envelope:
 *  {
 *    "before": { ... },          // previous row state (null on INSERT)
 *    "after":  { "id": 1, ... }, // current row state (null on DELETE)
 *    "source": { "table": "users", ... },
 *    "op":     "c"               // c=create, u=update, d=delete, r=read(snapshot)
 *  }
 *
 *  Transformer extracts "after" payload and writes:
 *    user:1 = {"id":1,"username":"alice",...}   (for users table)
 *    order:1 = {"id":1,"userId":1,...}          (for orders table)
 *
 *  On DELETE (op=d), removes the key from Redis.
 * </pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CdcEventTransformer {

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    @Value("${app.redis.key-prefix.user}")
    private String userKeyPrefix;

    @Value("${app.redis.key-prefix.order}")
    private String orderKeyPrefix;

    /**
     * Processes a raw Debezium CDC event JSON string. Extracts the table name from
     * the source field, determines the operation type, and either writes or deletes
     * the corresponding Redis key.
     *
     * @param eventJson the raw Debezium envelope JSON
     */
    public void processEvent(String eventJson) {
        try {
            JsonNode envelope = objectMapper.readTree(eventJson);
            JsonNode payload = envelope.has("payload") ? envelope.get("payload") : envelope;

            String operation = payload.has("op") ? payload.get("op").asText() : null;
            if (operation == null) {
                log.warn("CDC event missing 'op' field, skipping: {}", eventJson);
                return;
            }

            String table = extractTable(payload);
            if (table == null) {
                log.warn("CDC event missing source table, skipping");
                return;
            }

            String keyPrefix = resolveKeyPrefix(table);
            if (keyPrefix == null) {
                log.debug("Ignoring CDC event for untracked table: {}", table);
                return;
            }

            if ("d".equals(operation)) {
                handleDelete(payload, keyPrefix);
            } else {
                handleUpsert(payload, keyPrefix, operation);
            }
        } catch (JsonProcessingException e) {
            log.error("Failed to parse CDC event: {}", eventJson, e);
        }
    }

    private void handleUpsert(JsonNode payload, String keyPrefix, String operation) {
        JsonNode after = payload.get("after");
        if (after == null || after.isNull()) {
            log.warn("CDC upsert event has null 'after' field, skipping");
            return;
        }
        Long id = after.get("id").asLong();
        String key = keyPrefix + id;
        ObjectNode cleanNode = convertToCleanJson(after);
        String json = cleanNode.toString();
        redisTemplate.opsForValue().set(key, json);
        log.info("CDC {} key={} op={}", keyPrefix.contains("user") ? "User" : "Order", key, operation);
    }

    private void handleDelete(JsonNode payload, String keyPrefix) {
        JsonNode before = payload.get("before");
        if (before == null || before.isNull()) {
            log.warn("CDC delete event has null 'before' field, skipping");
            return;
        }
        Long id = before.get("id").asLong();
        String key = keyPrefix + id;
        redisTemplate.delete(key);
        log.info("CDC DELETE key={}", key);
    }

    private String extractTable(JsonNode payload) {
        JsonNode source = payload.get("source");
        if (source != null && source.has("table")) {
            return source.get("table").asText();
        }
        return null;
    }

    private String resolveKeyPrefix(String table) {
        return switch (table) {
            case "users" -> userKeyPrefix;
            case "orders" -> orderKeyPrefix;
            default -> null;
        };
    }

    private ObjectNode convertToCleanJson(JsonNode after) {
        ObjectNode node = objectMapper.createObjectNode();
        after.fields().forEachRemaining(entry -> {
            String fieldName = toCamelCase(entry.getKey());
            node.set(fieldName, entry.getValue());
        });
        return node;
    }

    private String toCamelCase(String snakeCase) {
        if (!snakeCase.contains("_")) {
            return snakeCase;
        }
        StringBuilder result = new StringBuilder();
        boolean capitalizeNext = false;
        for (char c : snakeCase.toCharArray()) {
            if (c == '_') {
                capitalizeNext = true;
            } else if (capitalizeNext) {
                result.append(Character.toUpperCase(c));
                capitalizeNext = false;
            } else {
                result.append(c);
            }
        }
        return result.toString();
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/com/example/cdc/consumer/CdcEventTransformer.java
git commit -m "feat: add CdcEventTransformer — shared Debezium envelope parser"
```

---

### Task 11: Redis Stream Consumer (Phase 1)

**Files:**
- Create: `src/main/java/com/example/cdc/consumer/RedisStreamConsumer.java`

- [ ] **Step 1: Create `RedisStreamConsumer.java`**

```java
package com.example.cdc.consumer;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.stream.StreamListener;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.data.redis.stream.Subscription;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * <b>Redis Stream Consumer (Phase 1)</b>
 *
 * <p>Listens on Redis Streams populated by Debezium Server's Redis Sink. Each stream
 * corresponds to a PostgreSQL table ({@code cdc_demo.public.users},
 * {@code cdc_demo.public.orders}). Delegates event processing to
 * {@link CdcEventTransformer}.
 *
 * <pre>
 *  Debezium Server ──> Redis Streams ──> RedisStreamConsumer ──> CdcEventTransformer ──> Redis Keys
 * </pre>
 *
 * <p>Active only when {@code debezium-server} profile is enabled.
 */
@Slf4j
@Component
@Profile("debezium-server")
@RequiredArgsConstructor
public class RedisStreamConsumer implements StreamListener<String, MapRecord<String, String, String>> {

    private final RedisTemplate<String, String> redisTemplate;
    private final CdcEventTransformer cdcEventTransformer;

    @Value("${app.cdc.redis-stream.users-stream}")
    private String usersStream;

    @Value("${app.cdc.redis-stream.orders-stream}")
    private String ordersStream;

    @Value("${app.cdc.redis-stream.consumer-group}")
    private String consumerGroup;

    @Value("${app.cdc.redis-stream.consumer-name}")
    private String consumerName;

    private StreamMessageListenerContainer<String, MapRecord<String, String, String>> container;
    private Subscription usersSubscription;
    private Subscription ordersSubscription;

    @PostConstruct
    public void start() {
        log.info("Starting Redis Stream CDC consumer (Phase 1)");
        createConsumerGroupIfNotExists(usersStream);
        createConsumerGroupIfNotExists(ordersStream);

        var options = StreamMessageListenerContainer.StreamMessageListenerContainerOptions
                .builder()
                .pollTimeout(Duration.ofSeconds(2))
                .build();

        container = StreamMessageListenerContainer.create(
                redisTemplate.getConnectionFactory(), options);

        usersSubscription = container.receive(
                Consumer.from(consumerGroup, consumerName),
                StreamOffset.create(usersStream, ReadOffset.lastConsumed()),
                this);

        ordersSubscription = container.receive(
                Consumer.from(consumerGroup, consumerName),
                StreamOffset.create(ordersStream, ReadOffset.lastConsumed()),
                this);

        container.start();
        log.info("Redis Stream consumer started — listening on streams: {}, {}",
                usersStream, ordersStream);
    }

    @PreDestroy
    public void stop() {
        if (container != null) {
            container.stop();
            log.info("Redis Stream consumer stopped");
        }
    }

    @Override
    public void onMessage(MapRecord<String, String, String> message) {
        try {
            String streamKey = message.getStream();
            String eventJson = message.getValue().values().iterator().next();
            log.debug("Received CDC event from stream={} id={}", streamKey, message.getId());
            cdcEventTransformer.processEvent(eventJson);
            redisTemplate.opsForStream().acknowledge(consumerGroup, message);
        } catch (Exception e) {
            log.error("Failed to process CDC event: {}", message.getId(), e);
        }
    }

    private void createConsumerGroupIfNotExists(String streamKey) {
        try {
            redisTemplate.opsForStream().createGroup(streamKey, consumerGroup);
            log.info("Created consumer group '{}' for stream '{}'", consumerGroup, streamKey);
        } catch (Exception e) {
            log.debug("Consumer group '{}' may already exist for stream '{}': {}",
                    consumerGroup, streamKey, e.getMessage());
        }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/com/example/cdc/consumer/RedisStreamConsumer.java
git commit -m "feat: add RedisStreamConsumer — Phase 1 CDC via Redis Streams"
```

---

### Task 12: Kafka CDC Consumer (Phase 2)

**Files:**
- Create: `src/main/java/com/example/cdc/consumer/KafkaCdcConsumer.java`

- [ ] **Step 1: Create `KafkaCdcConsumer.java`**

```java
package com.example.cdc.consumer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * <b>Kafka CDC Consumer (Phase 2)</b>
 *
 * <p>Listens on Kafka topics populated by Debezium Kafka Connect. Each topic corresponds
 * to a PostgreSQL table. Delegates event processing to {@link CdcEventTransformer}.
 *
 * <pre>
 *  Debezium Connect ──> Kafka ──> KafkaCdcConsumer ──> CdcEventTransformer ──> Redis Keys
 * </pre>
 *
 * <p>Active only when {@code kafka} profile is enabled.
 */
@Slf4j
@Component
@Profile("kafka")
@RequiredArgsConstructor
public class KafkaCdcConsumer {

    private final CdcEventTransformer cdcEventTransformer;

    @KafkaListener(topics = "${app.cdc.kafka.users-topic}", groupId = "cdc-demo-group")
    public void consumeUserEvent(String eventJson) {
        log.debug("Received user CDC event from Kafka");
        cdcEventTransformer.processEvent(eventJson);
    }

    @KafkaListener(topics = "${app.cdc.kafka.orders-topic}", groupId = "cdc-demo-group")
    public void consumeOrderEvent(String eventJson) {
        log.debug("Received order CDC event from Kafka");
        cdcEventTransformer.processEvent(eventJson);
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/com/example/cdc/consumer/KafkaCdcConsumer.java
git commit -m "feat: add KafkaCdcConsumer — Phase 2 CDC via Kafka topics"
```

---

### Task 13: Build and verify full compilation

- [ ] **Step 1: Run checkstyle + compile**

```bash
cd /Users/viskumar/Java_Workspace/Java-Posgtres-Debezium-Redis
./mvnw clean compile
```

Expected: `BUILD SUCCESS` with no checkstyle violations.

- [ ] **Step 2: Fix any issues and commit if needed**

---

### Task 14: K8s Manifests — PostgreSQL

**Files:**
- Create: `k8s/postgres/Dockerfile`
- Create: `k8s/postgres/postgresql.conf`
- Create: `k8s/postgres/deployment.yaml`
- Create: `k8s/postgres/service.yaml`

- [ ] **Step 1: Create `k8s/postgres/postgresql.conf`**

```
listen_addresses = '*'
wal_level = logical
max_wal_senders = 4
max_replication_slots = 4
```

- [ ] **Step 2: Create `k8s/postgres/Dockerfile`**

```dockerfile
FROM postgres:16

COPY postgresql.conf /etc/postgresql/postgresql.conf

CMD ["postgres", "-c", "config_file=/etc/postgresql/postgresql.conf"]
```

- [ ] **Step 3: Create `k8s/postgres/deployment.yaml`**

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: postgres
  labels:
    app: postgres
spec:
  replicas: 1
  selector:
    matchLabels:
      app: postgres
  template:
    metadata:
      labels:
        app: postgres
    spec:
      containers:
        - name: postgres
          image: cdc-postgres:latest
          imagePullPolicy: Never
          ports:
            - containerPort: 5432
          env:
            - name: POSTGRES_DB
              value: "cdc_demo"
            - name: POSTGRES_USER
              value: "postgres"
            - name: POSTGRES_PASSWORD
              value: "postgres"
          volumeMounts:
            - name: postgres-data
              mountPath: /var/lib/postgresql/data
      volumes:
        - name: postgres-data
          persistentVolumeClaim:
            claimName: postgres-pvc
---
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: postgres-pvc
spec:
  accessModes:
    - ReadWriteOnce
  resources:
    requests:
      storage: 1Gi
```

- [ ] **Step 4: Create `k8s/postgres/service.yaml`**

```yaml
apiVersion: v1
kind: Service
metadata:
  name: postgres-svc
spec:
  type: NodePort
  selector:
    app: postgres
  ports:
    - port: 5432
      targetPort: 5432
      nodePort: 30432
```

- [ ] **Step 5: Commit**

```bash
git add k8s/postgres/
git commit -m "feat: add PostgreSQL k8s manifests with WAL logical replication"
```

---

### Task 15: K8s Manifests — Debezium Server (Phase 1)

**Files:**
- Create: `k8s/debezium-server/deployment.yaml`
- Create: `k8s/debezium-server/service.yaml`

- [ ] **Step 1: Create `k8s/debezium-server/deployment.yaml`**

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: debezium-server
  labels:
    app: debezium-server
spec:
  replicas: 1
  selector:
    matchLabels:
      app: debezium-server
  template:
    metadata:
      labels:
        app: debezium-server
    spec:
      containers:
        - name: debezium-server
          image: quay.io/debezium/server:2.7
          ports:
            - containerPort: 8080
          env:
            - name: DEBEZIUM_SINK_TYPE
              value: "redis"
            - name: DEBEZIUM_SINK_REDIS_ADDRESS
              value: "host.k3d.internal:6379"
            - name: DEBEZIUM_SOURCE_CONNECTOR_CLASS
              value: "io.debezium.connector.postgresql.PostgresConnector"
            - name: DEBEZIUM_SOURCE_OFFSET_STORAGE_FILE_FILENAME
              value: "/debezium/data/offsets.dat"
            - name: DEBEZIUM_SOURCE_OFFSET_FLUSH_INTERVAL_MS
              value: "0"
            - name: DEBEZIUM_SOURCE_DATABASE_HOSTNAME
              value: "postgres-svc.default.svc.cluster.local"
            - name: DEBEZIUM_SOURCE_DATABASE_PORT
              value: "5432"
            - name: DEBEZIUM_SOURCE_DATABASE_USER
              value: "postgres"
            - name: DEBEZIUM_SOURCE_DATABASE_PASSWORD
              value: "postgres"
            - name: DEBEZIUM_SOURCE_DATABASE_DBNAME
              value: "cdc_demo"
            - name: DEBEZIUM_SOURCE_TOPIC_PREFIX
              value: "cdc_demo"
            - name: DEBEZIUM_SOURCE_TABLE_INCLUDE_LIST
              value: "public.users,public.orders"
            - name: DEBEZIUM_SOURCE_PLUGIN_NAME
              value: "pgoutput"
            - name: DEBEZIUM_SOURCE_DATABASE_SERVER_NAME
              value: "cdc_demo"
          volumeMounts:
            - name: debezium-data
              mountPath: /debezium/data
      volumes:
        - name: debezium-data
          emptyDir: {}
```

- [ ] **Step 2: Create `k8s/debezium-server/service.yaml`**

```yaml
apiVersion: v1
kind: Service
metadata:
  name: debezium-server-svc
spec:
  selector:
    app: debezium-server
  ports:
    - port: 8080
      targetPort: 8080
```

- [ ] **Step 3: Commit**

```bash
git add k8s/debezium-server/
git commit -m "feat: add Debezium Server k8s manifests (Phase 1 — Redis Sink)"
```

---

### Task 16: K8s Manifests — Kafka and Debezium Connect (Phase 2)

**Files:**
- Create: `k8s/kafka/deployment.yaml`
- Create: `k8s/kafka/service.yaml`
- Create: `k8s/kafka/debezium-connect/deployment.yaml`
- Create: `k8s/kafka/debezium-connect/service.yaml`
- Create: `k8s/kafka/debezium-connect/register-connector.json`

- [ ] **Step 1: Create `k8s/kafka/deployment.yaml`**

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: kafka
  labels:
    app: kafka
spec:
  replicas: 1
  selector:
    matchLabels:
      app: kafka
  template:
    metadata:
      labels:
        app: kafka
    spec:
      containers:
        - name: kafka
          image: apache/kafka:3.7.0
          ports:
            - containerPort: 9092
          env:
            - name: KAFKA_NODE_ID
              value: "1"
            - name: KAFKA_PROCESS_ROLES
              value: "broker,controller"
            - name: KAFKA_LISTENERS
              value: "PLAINTEXT://0.0.0.0:9092,CONTROLLER://0.0.0.0:9093"
            - name: KAFKA_ADVERTISED_LISTENERS
              value: "PLAINTEXT://kafka-svc.default.svc.cluster.local:9092"
            - name: KAFKA_CONTROLLER_LISTENER_NAMES
              value: "CONTROLLER"
            - name: KAFKA_LISTENER_SECURITY_PROTOCOL_MAP
              value: "CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT"
            - name: KAFKA_CONTROLLER_QUORUM_VOTERS
              value: "1@localhost:9093"
            - name: KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR
              value: "1"
            - name: KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR
              value: "1"
            - name: KAFKA_TRANSACTION_STATE_LOG_MIN_ISR
              value: "1"
            - name: KAFKA_LOG_DIRS
              value: "/tmp/kraft-combined-logs"
            - name: CLUSTER_ID
              value: "MkU3OEVBNTcwNTJENDM2Qk"
```

- [ ] **Step 2: Create `k8s/kafka/service.yaml`**

```yaml
apiVersion: v1
kind: Service
metadata:
  name: kafka-svc
spec:
  type: NodePort
  selector:
    app: kafka
  ports:
    - port: 9092
      targetPort: 9092
      nodePort: 30092
```

- [ ] **Step 3: Create `k8s/kafka/debezium-connect/deployment.yaml`**

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: debezium-connect
  labels:
    app: debezium-connect
spec:
  replicas: 1
  selector:
    matchLabels:
      app: debezium-connect
  template:
    metadata:
      labels:
        app: debezium-connect
    spec:
      containers:
        - name: debezium-connect
          image: quay.io/debezium/connect:2.7
          ports:
            - containerPort: 8083
          env:
            - name: BOOTSTRAP_SERVERS
              value: "kafka-svc.default.svc.cluster.local:9092"
            - name: GROUP_ID
              value: "cdc-connect-group"
            - name: CONFIG_STORAGE_TOPIC
              value: "cdc_connect_configs"
            - name: OFFSET_STORAGE_TOPIC
              value: "cdc_connect_offsets"
            - name: STATUS_STORAGE_TOPIC
              value: "cdc_connect_statuses"
            - name: CONFIG_STORAGE_REPLICATION_FACTOR
              value: "1"
            - name: OFFSET_STORAGE_REPLICATION_FACTOR
              value: "1"
            - name: STATUS_STORAGE_REPLICATION_FACTOR
              value: "1"
```

- [ ] **Step 4: Create `k8s/kafka/debezium-connect/service.yaml`**

```yaml
apiVersion: v1
kind: Service
metadata:
  name: debezium-connect-svc
spec:
  type: NodePort
  selector:
    app: debezium-connect
  ports:
    - port: 8083
      targetPort: 8083
      nodePort: 30083
```

- [ ] **Step 5: Create `k8s/kafka/debezium-connect/register-connector.json`**

```json
{
  "name": "cdc-postgres-connector",
  "config": {
    "connector.class": "io.debezium.connector.postgresql.PostgresConnector",
    "database.hostname": "postgres-svc.default.svc.cluster.local",
    "database.port": "5432",
    "database.user": "postgres",
    "database.password": "postgres",
    "database.dbname": "cdc_demo",
    "database.server.name": "cdc_demo",
    "topic.prefix": "cdc_demo",
    "table.include.list": "public.users,public.orders",
    "plugin.name": "pgoutput",
    "slot.name": "cdc_demo_slot",
    "publication.name": "cdc_demo_publication"
  }
}
```

- [ ] **Step 6: Commit**

```bash
git add k8s/kafka/
git commit -m "feat: add Kafka and Debezium Connect k8s manifests (Phase 2)"
```

---

### Task 17: Makefile

**Files:**
- Create: `Makefile`

- [ ] **Step 1: Create `Makefile`**

```makefile
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
```

- [ ] **Step 2: Commit**

```bash
git add Makefile
git commit -m "feat: add Makefile with k3d, postgres, debezium, kafka targets"
```

---

### Task 18: Maven Wrapper

- [ ] **Step 1: Generate Maven wrapper**

```bash
cd /Users/viskumar/Java_Workspace/Java-Posgtres-Debezium-Redis
mvn wrapper:wrapper -Dmaven=3.9.9
```

- [ ] **Step 2: Commit**

```bash
git add .mvn mvnw mvnw.cmd
git commit -m "feat: add Maven wrapper"
```

---

### Task 19: Final Build Verification

- [ ] **Step 1: Full compile with checkstyle**

```bash
cd /Users/viskumar/Java_Workspace/Java-Posgtres-Debezium-Redis
./mvnw clean compile
```

Expected: `BUILD SUCCESS`

- [ ] **Step 2: Fix any checkstyle or compilation errors**

- [ ] **Step 3: Final commit if any fixes were needed**

```bash
git add -A
git commit -m "fix: address checkstyle and compilation issues"
```

---

## Summary

| Task | Description | Files |
|------|-------------|-------|
| 1 | Project scaffolding | pom.xml, checkstyle.xml, configs, .gitignore, CLAUDE.md |
| 2 | Domain models | User.java, Order.java |
| 3 | DTOs | ApiResponse, UserRequest/Response, OrderRequest/Response, OrderStatusRequest |
| 4 | Exception handling | EntityNotFoundException, GlobalExceptionHandler |
| 5 | Redis config + repositories | RedisConfig, UserRepository, OrderRepository |
| 6 | Write services | UserWriteService, OrderWriteService |
| 7 | Read services | UserReadService, OrderReadService |
| 8 | Write controllers | UserWriteController, OrderWriteController |
| 9 | Read controllers | UserReadController, OrderReadController |
| 10 | CDC event transformer | CdcEventTransformer |
| 11 | Redis stream consumer | RedisStreamConsumer (Phase 1) |
| 12 | Kafka consumer | KafkaCdcConsumer (Phase 2) |
| 13 | Build verification | Compile + checkstyle |
| 14 | K8s — PostgreSQL | Dockerfile, postgresql.conf, deployment, service |
| 15 | K8s — Debezium Server | Deployment, service (Phase 1) |
| 16 | K8s — Kafka + Debezium Connect | Deployment, service, register-connector.json (Phase 2) |
| 17 | Makefile | All targets |
| 18 | Maven wrapper | mvnw |
| 19 | Final build verification | Full compile |
