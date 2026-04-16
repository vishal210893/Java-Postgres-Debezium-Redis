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
