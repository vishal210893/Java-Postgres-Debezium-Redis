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
