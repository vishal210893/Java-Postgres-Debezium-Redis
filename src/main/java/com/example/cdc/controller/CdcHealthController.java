package com.example.cdc.controller;

import com.example.cdc.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * <b>CDC Health Controller</b>
 *
 * <p>Exposes a health endpoint for the CDC pipeline, reporting Redis connectivity
 * and the active CDC mode (debezium-redis-sink or kafka).
 *
 * <pre>
 *  GET /api/health/cdc ──> { redis: UP/DOWN, cdcMode: "debezium-redis-sink"|"kafka"|"none" }
 * </pre>
 */
@Slf4j
@RestController
@RequestMapping("/api/health")
@RequiredArgsConstructor
@Tag(name = "CDC Health", description = "CDC pipeline health check")
public class CdcHealthController {

    private final RedisConnectionFactory redisConnectionFactory;
    private final Environment env;

    @Operation(summary = "Check CDC pipeline health")
    @GetMapping("/cdc")
    public ResponseEntity<ApiResponse<Map<String, String>>> cdcHealth() {
        Map<String, String> health = new LinkedHashMap<>();
        health.put("cdcMode", env.getProperty("app.cdc.mode", "none"));

        try {
            redisConnectionFactory.getConnection().ping();
            health.put("redis", "UP");
        } catch (Exception e) {
            health.put("redis", "DOWN");
            log.warn("Redis health check failed: {}", e.getMessage());
        }

        return ResponseEntity.ok(ApiResponse.ok(health));
    }
}
