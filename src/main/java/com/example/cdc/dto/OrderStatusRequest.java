package com.example.cdc.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * <b>Order Status Request DTO</b>
 *
 * <p>Request body for updating only the status of an existing order.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderStatusRequest {

    @NotBlank(message = "status is required")
    private String status;
}
