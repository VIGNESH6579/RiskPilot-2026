package com.riskpilot.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import com.riskpilot.model.MarketDataTransport;

@Data
@ConfigurationProperties(prefix = "riskpilot")
public class RiskPilotProperties {

    private String mode = "SHADOW"; // LIVE | SHADOW
    private boolean strictMode = true;
    private boolean enforceStrictTiming = true;

    private Session session = new Session();
    private Market market = new Market();
    private Instrument instrument = new Instrument();
    private Account account = new Account();
    private Filters filters = new Filters();
    private TimePhase timePhase = new TimePhase();
    private Risk risk = new Risk();
    private Execution execution = new Execution();
    private Infra infra = new Infra();
    private Notification notification = new Notification();

    public boolean isLiveMode() {
        return "LIVE".equalsIgnoreCase(mode);
    }

    public boolean isShadowMode() {
        return "SHADOW".equalsIgnoreCase(mode);
    }

    public boolean isRealFeedMode() {
        return isLiveMode() || isShadowMode();
    }

    public String dataSourceLabel() {
        return "REAL";
    }

    @Data
    public static class Session {
        private String timezone = "Asia/Kolkata";
        private String start = "09:15";
        private String end = "15:30";
        private String entryStart = "09:20";
        private String lastEntryCutoff = "15:00";
        private String forceExit = "15:25";
        private String openingRangeEnd = "09:45";
    }

    @Data
    public static class Market {
        private String open = "09:15";
        private String close = "15:30";
        private String zone = "Asia/Kolkata";
    }

    @Data
    public static class Instrument {
        private String symbol = "NIFTY";
        private int lotSize = 75;
        private double pointValue = 1.0;
        // NIFTY weekly expiry day. As of the SEBI single-weekly-expiry
        // framework (effective 2025-09 onwards) NSE settles NIFTY weekly
        // options on Tuesday. Override with INSTRUMENT_EXPIRY_DAY_OF_WEEK
        // if the exchange schedule changes again.
        private String expiryDayOfWeek = "TUESDAY";
    }

    @Data
    public static class Account {
        private double initialCapital = 500000.0;
        private double riskPerTradePct = 1.0;
        private double dailyLossLimitPct = 2.0;
        private int sizeReductionAfterLosses = 2;
        private double reducedRiskFactor = 0.5;
    }

    @Data
    public static class Filters {
        private String regimeRequired = "TREND_ONLY";
        private double minOrRange = 120;
        private double maxSpread = 2.0;
    }

    @Data
    public static class TimePhase {
        private Phase early = new Phase("09:15", "12:00", 1.0);
        private Phase mid = new Phase("12:00", "13:30", 0.35);
        private LatePhase late = new LatePhase("13:30", "15:30", false, true);

        @Data
        public static class Phase {
            public Phase(String start, String end, Double positionScale) {
                this.start = start;
                this.end = end;
                this.positionScale = positionScale;
            }
            private String start;
            private String end;
            private Double positionScale;
        }

        @Data
        public static class LatePhase {
            public LatePhase(String start, String end, Boolean allowNewTrades, Boolean forceExit) {
                this.start = start;
                this.end = end;
                this.allowNewTrades = allowNewTrades;
                this.forceExit = forceExit;
            }
            private String start;
            private String end;
            private Boolean allowNewTrades;
            private Boolean forceExit;
        }
    }

    @Data
    public static class Risk {
        // No artificial cap on trades per day — the strategy/risk gate decides.
        // Kept as a sane upper bound to prevent runaway loops; configurable via env.
        private int maxTradesPerDay = 100;
        private boolean oneTradeAtATime = true;
        private double maxDailyLossR = 1.5;
        private int maxConsecutiveLosses = 3;
    }

    @Data
    public static class Execution {
        private Slippage slippage = new Slippage();
        private Latency latency = new Latency();
        private Simulation simulation = new Simulation();
        private boolean rejectOnHighSlippage = true;
        private boolean rejectOnLatencyBreach = true;

        @Data
        public static class Slippage {
            private double entryMax = 2.0;
            private double tp1Max = 3.0;
            private double runnerMax = 6.0;
            private double panicExitMax = 8.0;

            /**
             * Selects the {@code SlippageModel} used by
             * {@code ExecutionSimulator}. Valid values: {@code FIXED_TICK}
             * or {@code ATR_FRACTION}. Default is {@code ATR_FRACTION}
             * because it is more realistic for index futures whose
             * spread cost scales with realised volatility.
             */
            private String model = "ATR_FRACTION";

            /**
             * Slippage in points used by the FIXED_TICK model. Should
             * sit between {@code simulation.slippage-min-points} and
             * {@code simulation.slippage-max-points}; values outside
             * the envelope are clamped at runtime.
             */
            private double fixedTickPoints = 1.0;

            /**
             * Multiplier applied to the rolling ATR (in points) by the
             * ATR_FRACTION model. 0.05 means 5% of ATR per fill — a
             * defensible starting point for NIFTY.
             */
            private double atrFraction = 0.05;
        }

        @Data
        public static class Latency {
            private long softBlockMs = 500L;
            private long hardBlockMs = 1500L;
            private long panicMs = 5000L;
        }

        @Data
        public static class Simulation {
            private long entryLatencyMinMs = 50L;
            private long entryLatencyMaxMs = 300L;
            private long exitLatencyMinMs = 50L;
            private long exitLatencyMaxMs = 250L;
            private double spreadMinPoints = 0.5;
            private double spreadMaxPoints = 2.0;
            private double slippageMinPoints = 0.1;
            private double slippageMaxPoints = 3.0;
            private double volatilityWeight = 0.08;
            private double tickSpeedWeight = 0.35;

            /**
             * Orders >= this many lots are split into two fill legs to
             * model the realistic case where the second half of size
             * crosses the spread at a worse price after the first leg
             * walks the book. Set to 0 to disable partial-fill
             * simulation entirely (single leg always).
             */
            private int partialFillThresholdLots = 20;

            /**
             * Additional adverse drift (in points) applied to the
             * second leg of a partial fill on top of the second leg's
             * own slippage calculation. Models book-walking: the
             * second half of size eats deeper liquidity that was sitting
             * behind the top of book.
             */
            private double partialFillSecondLegDriftPoints = 0.5;

            /**
             * Extra latency (in ms) added to the second leg of a
             * partial fill to model the time it takes the venue to
             * report each leg back. Plain delta on top of the leg-1
             * latency, never randomised.
             */
            private long partialFillSecondLegLatencyMs = 100L;
        }
    }

    @Data
    public static class Infra {
        private Feed feed = new Feed();
        private Heartbeat heartbeat = new Heartbeat();

        @Data
        public static class Feed {
            private boolean requireStable = true;
            private int maxMissingTicks = 3;
            private int instabilityTimeoutSec = 15;
            private boolean realTimeOnly = true;
            private String dataSource = "angelone-live";
            private MarketDataTransport transport = MarketDataTransport.WEBSOCKET;
            private long maxSourceAgeMs = 1500L;
            private long maxClockSkewMs = 2000L;
            private long startupValidTickTimeoutMs = 10000L;
            private boolean startupFailFast = true;
        }

        @Data
        public static class Heartbeat {
            private boolean enabled = true;
            private boolean panicExitOnFailure = true;
            private int timeoutSec = 30;
            private long maxSilenceMs = 3000L;
        }
    }

    @Data
    public static class Notification {
        private boolean ntfyEnabled = false;
        private String ntfyTopic = "riskpilot_shadow_alerts";
        private int ntfyCooldownSec = 10;
    }
}
