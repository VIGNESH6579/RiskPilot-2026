package com.riskpilot.event;

import com.riskpilot.model.Candle;

public record CandleClosedEvent(Candle candle) {
}
