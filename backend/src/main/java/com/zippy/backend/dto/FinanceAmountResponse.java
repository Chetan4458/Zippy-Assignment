package com.zippy.backend.dto;

import java.math.BigDecimal;

public record FinanceAmountResponse(
    long count,
    BigDecimal amount
) {}
