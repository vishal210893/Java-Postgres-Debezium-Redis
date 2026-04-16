package com.example.cdc.repository;

import com.example.cdc.model.User;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * <b>User Repository</b>
 *
 * <p>Spring Data JPA repository for {@link User} CRUD operations.
 */
public interface UserRepository extends JpaRepository<User, Long> {
}
