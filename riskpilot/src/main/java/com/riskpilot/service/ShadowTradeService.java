package com.riskpilot.service;

import com.riskpilot.model.Trade;
import com.riskpilot.repository.TradeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

@Slf4j
@Service
@RequiredArgsConstructor
public class ShadowTradeService {

    private final TradeRepository tradeRepository;
    private final Queue<Trade> pendingSaves = new ConcurrentLinkedQueue<>();
    private volatile boolean dbHealthy = true;

    @Transactional
    public Trade saveShadowTrade(Trade trade) {
        try {
            Trade saved = tradeRepository.saveAndFlush(trade);
            dbHealthy = true;
            return saved;
        } catch (DataAccessException e) {
            log.error("DB save failed: {}", e.getMessage());
            pendingSaves.add(trade);
            dbHealthy = false;
            return trade;
        }
    }

    @Scheduled(fixedDelay = 10000)
    @Transactional
    public void retryPendingSaves() {
        if (dbHealthy || pendingSaves.isEmpty()) {
            return;
        }

        try {
            Trade first = pendingSaves.peek();
            if (first == null) {
                return;
            }
            tradeRepository.saveAndFlush(first);
            pendingSaves.remove(first);
            dbHealthy = true;
            log.info("DB reconnected. Flushing pending shadow trade saves.");

            Trade trade;
            while ((trade = pendingSaves.poll()) != null) {
                tradeRepository.saveAndFlush(trade);
            }
        } catch (DataAccessException e) {
            dbHealthy = false;
            log.warn("DB reconnection failed - will retry pending shadow trade saves on next cycle");
        }
    }

    public int pendingSaveCount() {
        return pendingSaves.size();
    }
}
