package com.zippy.backend.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

public record OrderCreateRequest(
    @NotBlank String merchantOrderId,
    @Valid @NotNull Customer customer,
    @Valid @NotNull Address pickupAddress,
    @Valid @NotNull Address deliveryAddress,
    @Valid @NotNull @JsonProperty("package") PackageDetails packageDetails,
    @NotBlank String paymentType,
    BigDecimal codAmount
) {
  public record Customer(
      @NotBlank String name,
      @NotBlank String phone,
      @NotBlank String email
  ) {}

  public record Address(
      @NotBlank String addressLine1,
      @NotBlank String city,
      @NotBlank String state,
      @NotBlank String pincode
  ) {}

    public record PackageDetails(
      @NotNull @Positive BigDecimal weightGrams,
      @NotNull @Positive BigDecimal lengthCm,
      @NotNull @Positive BigDecimal widthCm,
      @NotNull @Positive BigDecimal heightCm
  ) {}
}
