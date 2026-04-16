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
