package com.example.cdc.repository;

import com.example.cdc.model.Order;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * <b>Order Repository</b>
 *
 * <p>Spring Data JPA repository for {@link Order} CRUD operations.
 */
public interface OrderRepository extends JpaRepository<Order, Long> {

    List<Order> findByUserId(Long userId);
}
