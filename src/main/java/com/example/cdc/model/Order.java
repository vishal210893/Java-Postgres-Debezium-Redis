package com.example.cdc.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * <b>Order JPA Entity</b>
 *
 * <p>Persistent representation of an order in the {@code orders} table. References a
 * {@link User} by {@code userId}. Changes are captured by Debezium CDC and streamed
 * to Redis as JSON strings keyed {@code order:{id}}.
 *
 * <pre>
 *  Table: orders
 *  ┌────┬─────────┬────────┬───────────┬─────────────┬────────────┬────────────┐
 *  │ id │ user_id │ amount │ status    │ description │ created_at │ updated_at │
 *  ├────┼─────────┼────────┼───────────┼─────────────┼────────────┼────────────┤
 *  │  1 │       1 │  99.99 │ CONFIRMED │ Laptop      │ ...        │ ...        │
 *  │  2 │       1 │  29.50 │ PENDING   │ Mouse       │ ...        │ ...        │
 *  └────┴─────────┴────────┴───────────┴─────────────┴────────────┴────────────┘
 * </pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "orders")
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false)
    private String status;

    private String description;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
