package com.riskpilot.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Append-only audit ledger for every shadow fill leg.
 *
 * <p>The runtime {@link Trade} row is mutable — its {@code stopLoss},
 * {@code remainingQuantity}, {@code actualExitPrice} all evolve as the
 * trade progresses. That means after the fact you cannot reconstruct
 * <em>why</em> a closed trade booked the P&amp;L it did from the trades
 * table alone. This ledger captures one row per fill leg (entry leg,
 * exit leg, plus the second leg of a partial fill when applicable) and
 * is never updated or deleted.
 *
 * <p>Two operational uses:
 * <ol>
 *   <li>Audit: reconstruct the complete fill timeline of any trade.</li>
 *   <li>Model calibration: the {@code modelUsed} +
 *       {@code expected_price} vs {@code fill_price} columns let us
 *       measure the gap between what the slippage model predicted and
 *       what would have been seen in production, and tune the model
 *       parameters accordingly.</li>
 * </ol>
 */
@Entity
@Table(
    name = "shadow_fill_events",
    indexes = {
        @Index(name = "idx_shadow_fill_trade", columnList = "trade_id"),
        @Index(name = "idx_shadow_fill_occurred_at", columnList = "occurred_at")
    }
)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ShadowFillEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * FK-by-convention into {@code trades.id}. Not declared as a JPA
     * association because the ledger must be writable even when the
     * trade entity is not loaded into the persistence context, and we
     * never want a cascade from this side.
     */
    @Column(name = "trade_id", nullable = false)
    private Long tradeId;

    /** ENTRY or EXIT. */
    @Column(name = "side", nullable = false, length = 10)
    private String side;

    /**
     * 1-based leg number. Single-fill orders always have legNumber=1
     * and totalLegs=1. A partial fill produces (1, 2) and (2, 2).
     */
    @Column(name = "leg_number", nullable = false)
    private Integer legNumber;

    @Column(name = "total_legs", nullable = false)
    private Integer totalLegs;

    @Column(name = "lots", nullable = false)
    private Integer lots;

    @Column(name = "expected_price", nullable = false, precision = 12, scale = 4)
    private BigDecimal expectedPrice;

    @Column(name = "fill_price", nullable = false, precision = 12, scale = 4)
    private BigDecimal fillPrice;

    /** Signed: positive = adverse to trade direction, negative = price improvement. */
    @Column(name = "slippage_points", nullable = false, precision = 12, scale = 4)
    private BigDecimal slippagePoints;

    @Column(name = "spread_points", nullable = false, precision = 12, scale = 4)
    private BigDecimal spreadPoints;

    @Column(name = "latency_ms", nullable = false)
    private Long latencyMs;

    /** Identifier of the {@code SlippageModel} used (e.g. {@code ATR_FRACTION}). */
    @Column(name = "slippage_model", nullable = false, length = 32)
    private String slippageModel;

    /** Trade direction at the time of fill: LONG or SHORT. */
    @Column(name = "direction", nullable = false, length = 10)
    private String direction;

    @Column(name = "exit_reason", length = 64)
    private String exitReason;

    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;
}
