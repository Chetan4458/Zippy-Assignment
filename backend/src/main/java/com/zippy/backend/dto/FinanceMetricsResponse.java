package com.zippy.backend.dto;

import java.math.BigDecimal;

public record FinanceMetricsResponse(
    String currency,
    BigDecimal grossCollected,
    BigDecimal refunds,
    BigDecimal netCollected,
    FinanceAmountResponse pendingIntent,
    FinanceAmountResponse failed,
    FinanceAmountResponse voided,
    FinanceAmountResponse codOutstanding,
    BigDecimal shipmentCost,
    long costedShipmentCount,
    long activeShipmentCount
) {}
