package com.zippy.backend.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record OrderCreateRequest(
    @NotBlank @Size(max = 64) String merchantOrderId,
    @Valid @NotNull Customer customer,
    @Valid @NotNull Address pickupAddress,
    @Valid @NotNull Address deliveryAddress,
    @Valid @NotNull @JsonProperty("package") PackageDetails packageDetails,
    @NotBlank @Pattern(regexp = "(?i)COD|PREPAID", message = "must be COD or PREPAID") String paymentType,
    @DecimalMin(value = "0.01") @Digits(integer = 8, fraction = 2) BigDecimal codAmount
) {
  public record Customer(
      @NotBlank @Size(max = 120) String name,
      @NotBlank @Size(max = 20)
      @Pattern(regexp = "^(?:\\+91[- ]?)?[6-9][0-9]{9}$", message = "must be a valid Indian phone number") String phone,
      @NotBlank @Email @Size(max = 120) String email
  ) {}

  public record Address(
      @NotBlank @Size(max = 255) String addressLine1,
      @NotBlank @Size(max = 100) String city,
      @NotBlank @Size(max = 100) String state,
      @NotBlank @Pattern(regexp = "^[1-9][0-9]{5}$", message = "must be a valid Indian pincode") String pincode
  ) {}

  public record PackageDetails(
      @NotNull @Positive @Digits(integer = 7, fraction = 0)
      @DecimalMax(value = "10000000") BigDecimal weightGrams,
      @NotNull @Positive @Digits(integer = 7, fraction = 2) BigDecimal lengthCm,
      @NotNull @Positive @Digits(integer = 7, fraction = 2) BigDecimal widthCm,
      @NotNull @Positive @Digits(integer = 7, fraction = 2) BigDecimal heightCm
  ) {}
}
