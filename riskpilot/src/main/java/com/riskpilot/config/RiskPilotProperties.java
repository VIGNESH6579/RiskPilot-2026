package com.riskpilot.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "riskpilot")
public class RiskPilotProperties {

    private String mode = "SHADOW"; // SHADOW | LIVE | REPLAY
    private boolean strictMode = true;

    private Session session = new Session();
    private Filters filters = new Filters();
    private TimePhase timePhase = new TimePhase();
    private Risk risk = new Risk();
    private Execution execution = new Execution();
    private Infra infra = new Infra();
    private Notification notification = new Notification();

    @Data
    public static class Session {
        private String timezone = "Asia/Kolkata";
        private String start = "09:15";
        private String end = "15:30";
        private String openingRangeEnd = "09:45";
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
        private int maxTradesPerDay = 2;
        private boolean oneTradeAtATime = true;
        private double maxDailyLossR = 1.5;
        private int maxConsecutiveLosses = 3;
    }

    @Data
    public static class Execution {
        private Slippage slippage = new Slippage();
        private Latency latency = new Latency();
        private boolean rejectOnHighSlippage = true;
        private boolean rejectOnLatencyBreach = true;

        @Data
        public static class Slippage {
            private double entryMax = 2.0;
            private double tp1Max = 3.0;
            private double runnerMax = 6.0;
            private double panicExitMax = 8.0;
        }

        @Data
        public static class Latency {
            private long softBlockMs = 500L;
            private long hardBlockMs = 1500L;
            private long panicMs = 5000L;
        }
    }

    @Data
    public static class Infra {
        private Feed feed = new Feed();
        private Heartbeat heartbeat = new Heartbeat();
        private MarketData marketData = new MarketData();

        @Data
        public static class Feed {
            private boolean requireStable = true;
            private int maxMissingTicks = 3;
            private int instabilityTimeoutSec = 15;
            private boolean realTimeOnly = true;  // NO CSV, NO MOCKS, NO REPLAY
            private String dataSource = "angelone-live";  // ONLY Angel One live feed
        }

        @Data
        public static class Heartbeat {
            private boolean enabled = true;
            private boolean panicExitOnFailure = true;
            private int timeoutSec = 30;
        }

        @Data
        public static class MarketData {
            private long cacheTtlSeconds = 5;
            private long refreshIntervalSeconds = 2;
            private boolean fallbackDisabled = true;  // NO FALLBACKS
            private boolean mockDisabled = true;     // NO MOCKS
        }
    }

    @Data
    public static class Notification {
        private boolean ntfyEnabled = false;
        private String ntfyTopic = "riskpilot_shadow_alerts";
        private int ntfyCooldownSec = 10;
    }
}
