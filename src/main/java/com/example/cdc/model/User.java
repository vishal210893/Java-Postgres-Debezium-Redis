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

import java.time.LocalDateTime;

/**
 * <b>User JPA Entity</b>
 *
 * <p>Persistent representation of a user in the {@code users} table. Changes to this table
 * are captured by Debezium CDC and streamed to Redis as JSON strings keyed {@code user:{id}}.
 *
 * <pre>
 *  Table: users
 *  ┌────┬──────────┬─────────────────┬─────────┬────────────┬────────────┐
 *  │ id │ username │ email           │ role    │ created_at │ updated_at │
 *  ├────┼──────────┼─────────────────┼─────────┼────────────┼────────────┤
 *  │  1 │ alice    │ alice@email.com │ USER    │ ...        │ ...        │
 *  │  2 │ bob      │ bob@email.com   │ ADMIN   │ ...        │ ...        │
 *  └────┴──────────┴─────────────────┴─────────┴────────────┴────────────┘
 * </pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String username;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(nullable = false)
    private String role;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
