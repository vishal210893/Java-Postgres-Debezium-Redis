package com.example.cdc.service;

import com.example.cdc.dto.UserRequest;
import com.example.cdc.dto.UserResponse;
import com.example.cdc.exception.EntityNotFoundException;
import com.example.cdc.model.User;
import com.example.cdc.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * <b>User Write Service</b>
 *
 * <p>Handles create, update, and delete operations for {@link User} entities in PostgreSQL.
 * Changes written here are captured by Debezium CDC and streamed to Redis.
 *
 * <pre>
 *  Controller ──> UserWriteService ──> UserRepository ──> PostgreSQL
 *                                                              │
 *                                                    Debezium reads WAL
 * </pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserWriteService {

    private final UserRepository userRepository;

    @Transactional
    public UserResponse create(UserRequest request) {
        User user = User.builder()
                .username(request.getUsername())
                .email(request.getEmail())
                .role(request.getRole())
                .build();
        User saved = userRepository.save(user);
        log.info("Created user id={} username={}", saved.getId(), saved.getUsername());
        return toResponse(saved);
    }

    @Transactional
    public UserResponse update(Long id, UserRequest request) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("User", id));
        user.setUsername(request.getUsername());
        user.setEmail(request.getEmail());
        user.setRole(request.getRole());
        User saved = userRepository.save(user);
        log.info("Updated user id={}", saved.getId());
        return toResponse(saved);
    }

    @Transactional
    public void delete(Long id) {
        if (!userRepository.existsById(id)) {
            throw new EntityNotFoundException("User", id);
        }
        userRepository.deleteById(id);
        log.info("Deleted user id={}", id);
    }

    private UserResponse toResponse(User user) {
        return UserResponse.builder()
                .id(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .role(user.getRole())
                .createdAt(user.getCreatedAt())
                .updatedAt(user.getUpdatedAt())
                .build();
    }
}
