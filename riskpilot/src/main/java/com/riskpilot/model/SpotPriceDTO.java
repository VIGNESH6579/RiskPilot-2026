package com.riskpilot.model;

public record SpotPriceDTO(
    Double value,
    String status,
    long staleSeconds,
    String displayText
) {}
