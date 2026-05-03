package com.riskpilot.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.net.ntp.NTPUDPClient;
import org.apache.commons.net.ntp.TimeInfo;
import org.springframework.boot.CommandLineRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.InetAddress;

@Slf4j
@Component
public class StartupRunner implements CommandLineRunner {
    private static final long MAX_CLOCK_DRIFT_MS = 2_000L;

    @Override
    public void run(String... args) {
        validateClockSync();
    }

    @Scheduled(fixedDelay = 21_600_000L)
    public void validateClockSyncPeriodically() {
        validateClockSync();
    }

    private boolean validateClockSync() {
        try {
            NTPUDPClient client = new NTPUDPClient();
            client.setDefaultTimeout(5_000);
            client.open();
            try {
                TimeInfo info = client.getTime(InetAddress.getByName("time.google.com"));
                info.computeDetails();
                Long offset = info.getOffset();
                long driftMs = Math.abs(offset == null ? 0L : offset);

                if (driftMs > MAX_CLOCK_DRIFT_MS) {
                    log.error("CLOCK_DRIFT: {}ms exceeds {}ms limit. Sync the system clock with NTP.",
                        driftMs, MAX_CLOCK_DRIFT_MS);
                    return false;
                }

                log.info("Clock sync OK (drift: {}ms)", driftMs);
                return true;
            } finally {
                client.close();
            }
        } catch (Exception e) {
            log.warn("NTP check failed: {} - proceeding with caution", e.getMessage());
            return true;
        }
    }
}
