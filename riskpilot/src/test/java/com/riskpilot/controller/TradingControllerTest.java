package com.riskpilot.controller;

import com.riskpilot.engine.AdaptiveRegimeEngine;
import com.riskpilot.engine.KillSwitchEngine;
import com.riskpilot.model.Trade;
import com.riskpilot.model.TradingSignal;
import com.riskpilot.model.TradingSession;
import com.riskpilot.service.ShadowExecutionEngine;
import com.riskpilot.service.StrictValidationService;
import com.riskpilot.service.TradingSessionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(TradingController.class)
class TradingControllerTest {

    @Autowired
    private MockMvc mockMvc;
    
    @MockitoBean
    private ShadowExecutionEngine shadowExecutionEngine;
    
    @MockitoBean
    private TradingSessionService tradingSessionService;

    @MockitoBean
    private StrictValidationService strictValidationService;

    @MockitoBean
    private KillSwitchEngine killSwitchEngine;

    @MockitoBean
    private AdaptiveRegimeEngine adaptiveRegimeEngine;
    @Test
    void getTradingStatus_ReturnsOkStatus() throws Exception {
        mockMvc.perform(get("/api/v1/trading/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.engine").value("SHADOW_EXECUTION"))
                .andExpect(jsonPath("$.message").exists());
    }
    
    @Test
    void getCurrentSession_ExistingSession_ReturnsSession() throws Exception {
        TradingSession session = TradingSession.builder()
                .id(1L)
                .sessionDate(LocalDate.now())
                .symbol("NIFTY")
                .dailyOpen(new BigDecimal("46000"))
                .orHigh(new BigDecimal("46150"))
                .orLow(new BigDecimal("45950"))
                .orExpansion(new BigDecimal("200"))
                .sessionActive(true)
                .status("ACTIVE")
                .build();
        
        when(tradingSessionService.getCurrentSession("NIFTY"))
                .thenReturn(session);
        
        mockMvc.perform(get("/api/v1/trading/sessions/current")
                        .param("symbol", "NIFTY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.symbol").value("NIFTY"));
    }
    
    @Test
    void getCurrentSession_NoSession_ReturnsBadRequest() throws Exception {
        when(tradingSessionService.getCurrentSession("NIFTY"))
                .thenThrow(new RuntimeException("No active session found"));
        
        mockMvc.perform(get("/api/v1/trading/sessions/current")
                        .param("symbol", "NIFTY"))
                .andExpect(status().isBadRequest());
    }
    
    @Test
    void getActiveTrades_ReturnsTrades() throws Exception {
        Trade trade = Trade.builder()
                .id(1L)
                .symbol("NIFTY")
                .status("ACTIVE")
                .realizedPnL(BigDecimal.ZERO)
                .unrealizedPnL(BigDecimal.ZERO)
                .build();
        
        when(tradingSessionService.getActiveTrades("NIFTY"))
                .thenReturn(Arrays.asList(trade));
        
        mockMvc.perform(get("/api/v1/trading/trades/active")
                        .param("symbol", "NIFTY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].symbol").value("NIFTY"));
    }
    
    @Test
    void createManualSignal_ValidSignal_ReturnsSuccess() throws Exception {
        String signalJson = """
                {
                  "symbol": "NIFTY",
                  "direction": "LONG",
                  "expectedEntry": 46100,
                  "stopLoss": 46015,
                  "targetPrice": 46250,
                  "confidence": 75
                }
                """;
        
        mockMvc.perform(post("/api/v1/trading/signals/manual")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signalJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.message").value("Manual signal created successfully"));
        
        verify(tradingSessionService).processManualSignal(any(TradingSignal.class));
    }
    
    @Test
    void closeTrade_ExistingTrade_ReturnsSuccess() throws Exception {
        mockMvc.perform(post("/api/v1/trading/trades/1/close")
                        .param("reason", "MANUAL_CLOSE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.tradeId").value(1))
                .andExpect(jsonPath("$.message").value("Trade closed successfully"));
        
        verify(tradingSessionService).closeTrade(1L, "MANUAL_CLOSE");
    }
    
    @Test
    void getPerformanceMetrics_ReturnsMetrics() throws Exception {
        Map<String, Object> metrics = new HashMap<>();
        metrics.put("totalTrades", 10);
        metrics.put("totalPnL", new BigDecimal("500.50"));
        metrics.put("winRate", 60.0);
        metrics.put("avgTrade", new BigDecimal("50.05"));
        metrics.put("symbol", "NIFTY");
        metrics.put("period", "30 days");
        
        when(tradingSessionService.getPerformanceMetrics("NIFTY", 30))
                .thenReturn(metrics);
        
        mockMvc.perform(get("/api/v1/trading/metrics/performance")
                        .param("symbol", "NIFTY")
                        .param("days", "30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalTrades").value(10))
                .andExpect(jsonPath("$.winRate").value(60.0))
                .andExpect(jsonPath("$.symbol").value("NIFTY"));
    }
    
    @Test
    void restartEngine_ReturnsSuccess() throws Exception {
        mockMvc.perform(post("/api/v1/trading/engine/restart"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.message").value("Trading engine restarted successfully"));
        
        verify(shadowExecutionEngine).restart();
    }
}

