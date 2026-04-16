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

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Base64;
import java.util.Set;

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
 *    "before": { ... },
 *    "after":  { "id": 1, ... },
 *    "source": { "table": "users", ... },
 *    "op":     "c"
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

    private static final Set<String> TIMESTAMP_FIELDS = Set.of(
            "created_at", "updated_at"
    );
    private static final Set<String> DECIMAL_FIELDS = Set.of("amount");
    private static final int DECIMAL_SCALE = 2;

    private ObjectNode convertToCleanJson(JsonNode after) {
        ObjectNode node = objectMapper.createObjectNode();
        after.fields().forEachRemaining(entry -> {
            String fieldName = toCamelCase(entry.getKey());
            String rawKey = entry.getKey();
            JsonNode value = entry.getValue();

            if (TIMESTAMP_FIELDS.contains(rawKey) && value.isNumber()) {
                long microSeconds = value.asLong();
                Instant instant = Instant.ofEpochSecond(
                        microSeconds / 1_000_000, (microSeconds % 1_000_000) * 1_000);
                LocalDateTime ldt = LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
                node.put(fieldName, ldt.toString());
            } else if (DECIMAL_FIELDS.contains(rawKey) && value.isTextual()) {
                byte[] bytes = Base64.getDecoder().decode(value.asText());
                BigDecimal decimal = new BigDecimal(new BigInteger(bytes), DECIMAL_SCALE);
                node.put(fieldName, decimal);
            } else {
                node.set(fieldName, value);
            }
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
