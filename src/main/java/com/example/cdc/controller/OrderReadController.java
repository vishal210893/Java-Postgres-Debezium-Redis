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
