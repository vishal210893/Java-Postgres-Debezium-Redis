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
