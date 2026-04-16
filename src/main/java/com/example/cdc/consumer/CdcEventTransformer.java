package com.example.cdc.consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.base.CaseFormat;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Set;

/**
 * <b>CDC Event Transformer</b>
 *
 * <p>Shared component that parses Debezium CDC event envelopes and writes the transformed
 * data to Redis. Used by both {@link RedisStreamConsumer} (Phase 1) and
 * {@link KafkaCdcConsumer} (Phase 2).
 *
 * <p>Field name conversion from Debezium's snake_case to camelCase is handled by
 * Guava's {@link CaseFormat}. Timestamp conversion from Debezium's epoch millis
 * ({@code time.precision.mode=connect}) to ISO {@link LocalDateTime} uses standard
 * {@link Instant} APIs. Decimal handling is delegated to Debezium's
 * {@code decimal.handling.mode=string} configuration.
 *
 * <pre>
 *  Debezium CDC Event Envelope:
 *  {
 *    "before": { ... },
 *    "after":  { "id": 1, "user_id": 1, "created_at": 1713275445123, ... },
 *    "source": { "table": "orders", ... },
 *    "op":     "c"
 *  }
 *
 *  Transformer output to Redis:
 *    order:1 = {"id":1,"userId":1,"createdAt":"2026-04-16T23:30:45.123",...}
 * </pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CdcEventTransformer {

    private static final Set<String> TIMESTAMP_FIELDS = Set.of("created_at", "updated_at");

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
        redisTemplate.opsForValue().set(key, cleanNode.toString());
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

    private ObjectNode convertToCleanJson(JsonNode after) {
        ObjectNode node = objectMapper.createObjectNode();
        after.fields().forEachRemaining(entry -> {
            String camelKey = CaseFormat.LOWER_UNDERSCORE.to(CaseFormat.LOWER_CAMEL, entry.getKey());
            JsonNode value = entry.getValue();

            if (TIMESTAMP_FIELDS.contains(entry.getKey()) && value.isNumber()) {
                LocalDateTime ldt = LocalDateTime.ofInstant(
                        Instant.ofEpochMilli(value.asLong()), ZoneId.systemDefault());
                node.put(camelKey, ldt.toString());
            } else {
                node.set(camelKey, value);
            }
        });
        return node;
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
}
