package com.zippy.backend.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record DailyFinanceTrendResponse(
    LocalDate date,
    BigDecimal grossCollected,
    BigDecimal refunds,
    BigDecimal netCollected,
    long transactions
) {}
