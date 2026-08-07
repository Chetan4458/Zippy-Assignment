package com.zippy.backend.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record PaymentTransactionResponse(
    String transactionId,
    String paymentId,
    String orderId,
    String eventType,
    String previousStatus,
    String resultingStatus,
    BigDecimal amount,
    String currency,
    String provider,
    String providerReference,
    String reconciliationReference,
    String reason,
    String actor,
    Instant createdAt
) {}
