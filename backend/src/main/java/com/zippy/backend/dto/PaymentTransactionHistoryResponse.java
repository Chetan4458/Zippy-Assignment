package com.zippy.backend.dto;

import java.util.List;

public record PaymentTransactionHistoryResponse(
    String paymentId,
    String orderId,
    int limit,
    int offset,
    long totalTransactions,
    List<PaymentTransactionResponse> transactions
) {}
