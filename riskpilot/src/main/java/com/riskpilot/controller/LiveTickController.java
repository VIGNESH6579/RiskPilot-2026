package com.riskpilot.controller;

import com.riskpilot.model.TradingSessionSnapshot;
import com.riskpilot.service.CandleAggregator;
import com.riskpilot.service.SessionStateManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 🎯 CRITICAL: WebSocket Bridge from Frontend to Trading Engine
 * 
 * This receives LIVE price ticks and feeds them to the trading engine!
 */
@Controller
public class LiveTickController {
    private static final Logger logger = LoggerFactory.getLogger(LiveTickController.class);

    @Autowired
    private CandleAggregator candleAggregator;

    @Autowired
    private SessionStateManager stateManager;

    // External data injection endpoints removed to ensure only real-time data is used.
}
