package com.zippy.backend.dto;

import java.util.List;

public record PaymentHistoryResponse(
    int limit,
    int offset,
    long totalPayments,
    List<PaymentIntentResponse> payments,
    FinanceFilterResponse filters,
    FinanceMetricsResponse finance
) {}
