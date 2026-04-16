package com.example.cdc.service;

import com.example.cdc.dto.OrderRequest;
import com.example.cdc.dto.OrderResponse;
import com.example.cdc.dto.OrderStatusRequest;
import com.example.cdc.exception.EntityNotFoundException;
import com.example.cdc.model.Order;
import com.example.cdc.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * <b>Order Write Service</b>
 *
 * <p>Handles create, update, delete, and status-change operations for {@link Order} entities
 * in PostgreSQL. Changes are captured by Debezium CDC and streamed to Redis.
 *
 * <pre>
 *  Controller ──> OrderWriteService ──> OrderRepository ──> PostgreSQL
 *                                                                │
 *                                                      Debezium reads WAL
 * </pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderWriteService {

    private static final String DEFAULT_STATUS = "PENDING";
    private final OrderRepository orderRepository;

    @Transactional
    public OrderResponse create(OrderRequest request) {
        Order order = Order.builder()
                .userId(request.getUserId())
                .amount(request.getAmount())
                .status(request.getStatus() != null ? request.getStatus() : DEFAULT_STATUS)
                .description(request.getDescription())
                .build();
        Order saved = orderRepository.save(order);
        log.info("Created order id={} userId={}", saved.getId(), saved.getUserId());
        return toResponse(saved);
    }

    @Transactional
    public OrderResponse update(Long id, OrderRequest request) {
        Order order = orderRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Order", id));
        order.setUserId(request.getUserId());
        order.setAmount(request.getAmount());
        order.setStatus(request.getStatus() != null ? request.getStatus() : order.getStatus());
        order.setDescription(request.getDescription());
        Order saved = orderRepository.save(order);
        log.info("Updated order id={}", saved.getId());
        return toResponse(saved);
    }

    @Transactional
    public OrderResponse updateStatus(Long id, OrderStatusRequest request) {
        Order order = orderRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Order", id));
        order.setStatus(request.getStatus());
        Order saved = orderRepository.save(order);
        log.info("Updated order id={} status={}", saved.getId(), saved.getStatus());
        return toResponse(saved);
    }

    @Transactional
    public void delete(Long id) {
        if (!orderRepository.existsById(id)) {
            throw new EntityNotFoundException("Order", id);
        }
        orderRepository.deleteById(id);
        log.info("Deleted order id={}", id);
    }

    private OrderResponse toResponse(Order order) {
        return OrderResponse.builder()
                .id(order.getId())
                .userId(order.getUserId())
                .amount(order.getAmount())
                .status(order.getStatus())
                .description(order.getDescription())
                .createdAt(order.getCreatedAt())
                .updatedAt(order.getUpdatedAt())
                .build();
    }
}
