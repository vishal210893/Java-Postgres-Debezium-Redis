package com.example.cdc;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.core.env.Environment;
import org.springframework.kafka.annotation.EnableKafka;
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
@EnableKafka
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
