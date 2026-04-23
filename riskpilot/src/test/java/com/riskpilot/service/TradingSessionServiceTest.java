package com.riskpilot.service;

import com.riskpilot.model.Trade;
import com.riskpilot.model.TradingSignal;
import com.riskpilot.model.TradingSession;
import com.riskpilot.repository.TradeRepository;
import com.riskpilot.repository.TradingSignalRepository;
import com.riskpilot.repository.TradingSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TradingSessionServiceTest {

    @Mock
    private TradingSessionRepository sessionRepository;
    
    @Mock
    private TradeRepository tradeRepository;
    
    @Mock
    private TradingSignalRepository signalRepository;
    
    @InjectMocks
    private TradingSessionService tradingSessionService;
    
    private TradingSession testSession;
    private Trade testTrade;
    private TradingSignal testSignal;
    
    @BeforeEach
    void setUp() {
        testSession = TradingSession.builder()
                .id(1L)
                .sessionDate(LocalDate.now())
                .symbol("BANKNIFTY")
                .dailyOpen(new BigDecimal("46000"))
                .orHigh(new BigDecimal("46150"))
                .orLow(new BigDecimal("45950"))
                .build();
        
        testTrade = Trade.builder()
                .id(1L)
                .symbol("BANKNIFTY")
                .direction("LONG")
                .entryPrice(new BigDecimal("46100"))
                .stopLoss(new BigDecimal("46015"))
                .targetPrice(new BigDecimal("46250"))
                .positionSize(new BigDecimal("75"))
                .remainingSize(new BigDecimal("75"))
                .realizedPnL(BigDecimal.ZERO)
                .unrealizedPnL(BigDecimal.ZERO)
                .status("ACTIVE")
                .entryTime(LocalDateTime.now())
                .build();
        
        testSignal = TradingSignal.builder()
                .id(1L)
                .symbol("BANKNIFTY")
                .direction("LONG")
                .expectedEntry(new BigDecimal("46100"))
                .stopLoss(new BigDecimal("46015"))
                .targetPrice(new BigDecimal("46250"))
                .confidence(75)
                .regime("TREND")
                .timePhase("MIDDLE")
                .status("GENERATED")
                .signalTime(LocalDateTime.now())
                .build();
    }
    
    @Test
    void getCurrentSession_ExistingSession_ReturnsSession() {
        // Given
        when(sessionRepository.findActiveSession("BANKNIFTY"))
                .thenReturn(Optional.of(testSession));
        
        // When
        TradingSession result = tradingSessionService.getCurrentSession("BANKNIFTY");
        
        // Then
        assertNotNull(result);
        assertEquals("BANKNIFTY", result.getSymbol());
        assertEquals(LocalDate.now(), result.getSessionDate());
        verify(sessionRepository).findActiveSession("BANKNIFTY");
    }
    
    @Test
    void getCurrentSession_NoExistingSession_CreatesNewSession() {
        // Given
        when(sessionRepository.findActiveSession("BANKNIFTY"))
                .thenReturn(Optional.empty());
        when(sessionRepository.save(any(TradingSession.class)))
                .thenReturn(testSession);
        
        // When
        TradingSession result = tradingSessionService.getCurrentSession("BANKNIFTY");
        
        // Then
        assertNotNull(result);
        verify(sessionRepository).findActiveSession("BANKNIFTY");
        verify(sessionRepository).save(any(TradingSession.class));
    }
    
    @Test
    void getActiveTrades_ReturnsActiveTrades() {
        // Given
        List<Trade> expectedTrades = Arrays.asList(testTrade);
        when(tradeRepository.findActiveTradesBySymbol("BANKNIFTY"))
                .thenReturn(expectedTrades);
        
        // When
        List<Trade> result = tradingSessionService.getActiveTrades("BANKNIFTY");
        
        // Then
        assertEquals(1, result.size());
        assertEquals(testTrade.getId(), result.get(0).getId());
        verify(tradeRepository).findActiveTradesBySymbol("BANKNIFTY");
    }
    
    @Test
    void getRecentSignals_ReturnsRecentSignals() {
        // Given
        List<TradingSignal> expectedSignals = Arrays.asList(testSignal);
        when(signalRepository.findExecutableSignalsSince(eq("BANKNIFTY"), any(LocalDateTime.class)))
                .thenReturn(expectedSignals);
        
        // When
        List<TradingSignal> result = tradingSessionService.getRecentSignals("BANKNIFTY", 10);
        
        // Then
        assertEquals(1, result.size());
        assertEquals(testSignal.getId(), result.get(0).getId());
        verify(signalRepository).findExecutableSignalsSince(eq("BANKNIFTY"), any(LocalDateTime.class));
    }
    
    @Test
    void processManualSignal_ProcessesSignalSuccessfully() {
        // Given
        when(signalRepository.save(any(TradingSignal.class)))
                .thenReturn(testSignal);
        
        // When
        tradingSessionService.processManualSignal(testSignal);
        
        // Then
        verify(signalRepository).save(testSignal);
        assertEquals("MANUAL", testSignal.getStatus());
        assertNotNull(testSignal.getExecutionTime());
    }
    
    @Test
    void closeTrade_ExistingTrade_ClosesTradeSuccessfully() {
        // Given
        when(tradeRepository.findById(1L))
                .thenReturn(Optional.of(testTrade));
        when(tradeRepository.save(any(Trade.class)))
                .thenReturn(testTrade);
        when(tradeRepository.getTodayPnL("BANKNIFTY"))
                .thenReturn(new BigDecimal("100.50"));
        
        // When
        tradingSessionService.closeTrade(1L, "MANUAL_CLOSE");
        
        // Then
        verify(tradeRepository).findById(1L);
        verify(tradeRepository).save(testTrade);
        assertEquals("CLOSED", testTrade.getStatus());
        assertEquals("MANUAL_CLOSE", testTrade.getExitReason());
        assertNotNull(testTrade.getExitTime());
    }
    
    @Test
    void closeTrade_NonExistentTrade_ThrowsException() {
        // Given
        when(tradeRepository.findById(999L))
                .thenReturn(Optional.empty());
        
        // When & Then
        assertThrows(RuntimeException.class, () -> {
            tradingSessionService.closeTrade(999L, "MANUAL_CLOSE");
        });
    }
    
    @Test
    void getPerformanceMetrics_NoTrades_ReturnsZeroMetrics() {
        // Given
        when(tradeRepository.findBySymbolAndEntryTimeBetween(
                eq("BANKNIFTY"), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(Arrays.asList());
        
        // When
        var result = tradingSessionService.getPerformanceMetrics("BANKNIFTY", 30);
        
        // Then
        assertEquals(0, result.get("totalTrades"));
        assertEquals(BigDecimal.ZERO, result.get("totalPnL"));
        assertEquals(0.0, result.get("winRate"));
        assertEquals(BigDecimal.ZERO, result.get("avgTrade"));
    }
    
    @Test
    void getPerformanceMetrics_WithTrades_ReturnsCorrectMetrics() {
        // Given
        Trade winningTrade = createWinningTrade();
        Trade losingTrade = createLosingTrade();
        List<Trade> trades = Arrays.asList(winningTrade, losingTrade);
        
        when(tradeRepository.findBySymbolAndEntryTimeBetween(
                eq("BANKNIFTY"), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(trades);
        
        // When
        var result = tradingSessionService.getPerformanceMetrics("BANKNIFTY", 30);
        
        // Then
        assertEquals(2, result.get("totalTrades"));
        assertEquals(new BigDecimal("50.00"), result.get("totalPnL"));
        assertEquals(50.0, result.get("winRate"));
        assertEquals(new BigDecimal("25.00"), result.get("avgTrade"));
        assertEquals(1L, result.get("winningTrades"));
        assertEquals(1L, result.get("losingTrades"));
    }
    
    @Test
    void endSession_ActiveSession_EndsSessionSuccessfully() {
        // Given
        when(sessionRepository.findActiveSession("BANKNIFTY"))
                .thenReturn(Optional.of(testSession));
        when(sessionRepository.save(any(TradingSession.class)))
                .thenReturn(testSession);
        
        // When
        tradingSessionService.endSession("BANKNIFTY");
        
        // Then
        verify(sessionRepository).findActiveSession("BANKNIFTY");
        verify(sessionRepository).save(testSession);
        assertFalse(testSession.getSessionActive());
        assertEquals("CLOSED", testSession.getStatus());
        assertNotNull(testSession.getSessionEnd());
    }
    
    @Test
    void endSession_NoActiveSession_DoesNothing() {
        // Given
        when(sessionRepository.findActiveSession("BANKNIFTY"))
                .thenReturn(Optional.empty());
        
        // When
        tradingSessionService.endSession("BANKNIFTY");
        
        // Then
        verify(sessionRepository).findActiveSession("BANKNIFTY");
        verify(sessionRepository, never()).save(any(TradingSession.class));
    }
    
    private Trade createWinningTrade() {
        return Trade.builder()
                .id(2L)
                .symbol("BANKNIFTY")
                .direction("LONG")
                .entryPrice(new BigDecimal("46100"))
                .stopLoss(new BigDecimal("46015"))
                .targetPrice(new BigDecimal("46250"))
                .positionSize(new BigDecimal("75"))
                .realizedPnL(new BigDecimal("100.00"))
                .unrealizedPnL(BigDecimal.ZERO)
                .status("CLOSED")
                .entryTime(LocalDateTime.now().minusHours(2))
                .exitTime(LocalDateTime.now().minusHours(1))
                .build();
    }
    
    private Trade createLosingTrade() {
        return Trade.builder()
                .id(3L)
                .symbol("BANKNIFTY")
                .direction("LONG")
                .entryPrice(new BigDecimal("46200"))
                .stopLoss(new BigDecimal("46115"))
                .targetPrice(new BigDecimal("46350"))
                .positionSize(new BigDecimal("75"))
                .realizedPnL(new BigDecimal("-50.00"))
                .unrealizedPnL(BigDecimal.ZERO)
                .status("CLOSED")
                .entryTime(LocalDateTime.now().minusHours(4))
                .exitTime(LocalDateTime.now().minusHours(3))
                .build();
    }
}
