package com.zippy.backend.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record PaymentIntentResponse(
    String paymentId,
    String orderId,
    BigDecimal amount,
    String currency,
    String status,
    String paymentMethod,
    String collectionStage,
    String failureCode,
    String failureReason,
    BigDecimal refundedAmount,
    String provider,
    String providerReference,
    String reconciliationReference,
    String refundReference,
    String refundReconciliationReference,
    Instant capturedAt,
    Instant collectedAt,
    Instant refundedAt,
    Instant createdAt,
    Instant updatedAt
) {}
