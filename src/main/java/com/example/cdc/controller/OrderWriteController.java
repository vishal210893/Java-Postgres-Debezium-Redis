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
