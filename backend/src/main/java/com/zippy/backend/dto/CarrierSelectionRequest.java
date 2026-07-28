package com.zippy.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record CarrierSelectionRequest(
    @NotBlank String carrierCode,
    @NotBlank String serviceCode,
    @NotNull BigDecimal quotedAmount
) {}
