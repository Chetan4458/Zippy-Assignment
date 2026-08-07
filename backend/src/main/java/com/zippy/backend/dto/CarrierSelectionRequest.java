package com.zippy.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record CarrierSelectionRequest(
    @NotBlank @Size(max = 32) String carrierCode,
    @NotBlank @Size(max = 64) String serviceCode,
    @NotNull @Positive @Digits(integer = 8, fraction = 2) BigDecimal quotedAmount
) {}
