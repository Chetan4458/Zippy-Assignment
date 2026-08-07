package com.zippy.backend.dto;

import java.time.Instant;
import java.time.LocalDate;

public record FinanceFilterResponse(
    LocalDate from,
    LocalDate to,
    Instant fromInclusive,
    Instant toExclusive,
    String status,
    String method,
    String carrier,
    String search
) {}
