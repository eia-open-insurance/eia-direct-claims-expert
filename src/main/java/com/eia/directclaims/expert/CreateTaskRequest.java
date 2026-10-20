package com.eia.directclaims.expert;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * Command to open a claim task. {@code priority} is one of LOW, NORMAL, HIGH,
 * URGENT; {@code dueDate} is required; {@code assignedTo} is a free-form
 * adjuster reference.
 */
public record CreateTaskRequest(
        @NotBlank(message = "title is required")
        @Size(max = 200, message = "title must be at most 200 characters")
        String title,
        @Size(max = 4000, message = "description must be at most 4000 characters")
        String description,
        @NotNull(message = "dueDate is required")
        LocalDate dueDate,
        @NotBlank(message = "priority is required")
        String priority,
        @Size(max = 120, message = "assignedTo must be at most 120 characters")
        String assignedTo
) {
}
