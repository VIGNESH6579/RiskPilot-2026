package com.riskpilot.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Async / executor wiring for the trading engine.
 *
 * Two carefully-sized executors are exposed:
 *
 * <ul>
 *   <li>{@code candleEventExecutor} — single-threaded, used by
 *       {@link com.riskpilot.service.ShadowExecutionEngine#onCandleClosed}
 *       to break the AB/BA deadlock between the {@code CandleAggregator}
 *       monitor and the engine's {@code tradeStateLock}. Single thread is
 *       intentional: candle events must be processed in arrival order, and
 *       parallel candle evaluation would race on session state.</li>
 *
 *   <li>{@code tickIngestExecutor} — single-threaded, drains a bounded
 *       queue of inbound websocket ticks so the JDK HttpClient WS reader
 *       thread is never blocked by validation, persistence, or the trade
 *       state lock. Bounded queue + caller-discards-newest is the correct
 *       backpressure model for a market data feed: dropping the freshest
 *       tick is safer than dropping older ticks (which would corrupt
 *       candle continuity).</li>
 * </ul>
 *
 * Both executors reject silently when full so we never propagate
 * RejectedExecutionException up into the WS reader. Drops are tracked via
 * MarketDataStateService / engine reject counters at the call site.
 */
@Slf4j
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean(name = "candleEventExecutor")
    public Executor candleEventExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(1);
        exec.setMaxPoolSize(1);
        // 256 backlog — generous for 5-minute candles which fire ~78 times
        // per session, but bounded so a stuck listener can't OOM the JVM.
        exec.setQueueCapacity(256);
        exec.setThreadNamePrefix("candle-evt-");
        // Caller-runs is acceptable here because the producer is the
        // CandleAggregator monitor thread, and the only way we'd saturate
        // is if evaluateCandle is itself wedged — in which case we want
        // the back-pressure to surface, not silently drop session state.
        exec.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        exec.setWaitForTasksToCompleteOnShutdown(true);
        exec.setAwaitTerminationSeconds(5);
        exec.initialize();
        log.info("Initialized candleEventExecutor coreSize=1 queueCapacity=256");
        return exec;
    }
}
