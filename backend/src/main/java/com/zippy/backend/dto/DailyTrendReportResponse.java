package com.zippy.backend.dto;

import java.util.List;

public record DailyTrendReportResponse(
    FinanceFilterResponse filters,
    List<DailyFinanceTrendResponse> dailyTrend
) {}
