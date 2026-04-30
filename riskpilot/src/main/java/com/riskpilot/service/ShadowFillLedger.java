package com.riskpilot.service;

import com.riskpilot.model.ShadowFillEvent;
import com.riskpilot.repository.ShadowFillEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Append-only writer for {@link ShadowFillEvent}.
 *
 * <p>Two contracts that callers can rely on:
 * <ul>
 *   <li><b>Never throws.</b> The ledger exists to <em>record</em> trade
 *       reality; it must never become a reason a trade fails to open or
 *       close. Persistence failures are logged at ERROR with full
 *       stacktrace and counted on {@link #failureCount()}.</li>
 *   <li><b>Always uses {@link Propagation#REQUIRES_NEW}.</b> We do not
 *       want a ledger insert to enlist into the surrounding trade
 *       transaction — a rollback in trade persistence must not roll
 *       back our audit trail, and conversely a ledger failure must
 *       never roll back a trade write.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShadowFillLedger {

    private final ShadowFillEventRepository repository;
    private final AtomicLong successCount = new AtomicLong();
    private final AtomicLong failureCount = new AtomicLong();

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(ShadowFillEvent event) {
        if (event == null) {
            return;
        }
        try {
            if (event.getOccurredAt() == null) {
                event.setOccurredAt(LocalDateTime.now());
            }
            repository.save(event);
            successCount.incrementAndGet();
        } catch (Exception e) {
            long failures = failureCount.incrementAndGet();
            // Keep the failure visible but do NOT propagate — the trade
            // open/close path must not be torn down by a ledger fault.
            log.error(
                "SHADOW_FILL_LEDGER_WRITE_FAILED tradeId={} side={} leg={}/{} totalFailures={} reason={}",
                event.getTradeId(),
                event.getSide(),
                event.getLegNumber(),
                event.getTotalLegs(),
                failures,
                e.getMessage(),
                e
            );
        }
    }

    @Transactional(readOnly = true)
    public List<ShadowFillEvent> findForTrade(Long tradeId) {
        return repository.findByTradeIdOrderByLegNumberAsc(tradeId);
    }

    public long successCount() {
        return successCount.get();
    }

    public long failureCount() {
        return failureCount.get();
    }

    /**
     * Volume-weighted average fill price across all legs of the same
     * {@code (tradeId, side)}. Useful for downstream reporting that
     * needs a single execution price for a partial-filled order. Returns
     * {@code null} when no events exist.
     */
    @Transactional(readOnly = true)
    public BigDecimal vwap(Long tradeId, String side) {
        List<ShadowFillEvent> legs = repository.findByTradeIdOrderByLegNumberAsc(tradeId);
        BigDecimal totalNotional = BigDecimal.ZERO;
        long totalLots = 0L;
        for (ShadowFillEvent leg : legs) {
            if (!side.equalsIgnoreCase(leg.getSide())) {
                continue;
            }
            int lots = leg.getLots() == null ? 0 : leg.getLots();
            if (lots <= 0 || leg.getFillPrice() == null) {
                continue;
            }
            totalNotional = totalNotional.add(leg.getFillPrice().multiply(BigDecimal.valueOf(lots)));
            totalLots += lots;
        }
        if (totalLots == 0L) {
            return null;
        }
        return totalNotional.divide(BigDecimal.valueOf(totalLots), 4, RoundingMode.HALF_UP);
    }
}
