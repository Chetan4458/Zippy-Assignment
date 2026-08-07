package com.zippy.backend.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record PaymentFailureRequest(
    @Size(max = 64)
    @Pattern(regexp = "^[A-Za-z0-9_.-]*$", message = "may contain only letters, numbers, dot, underscore, or hyphen")
    String code,
    @Size(max = 255) String reason,
    @Size(max = 128)
    @Pattern(regexp = "^[A-Za-z0-9_./:-]*$", message = "contains unsupported characters")
    String reference
) {}
