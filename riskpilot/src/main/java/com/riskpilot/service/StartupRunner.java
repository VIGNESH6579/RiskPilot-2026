package com.riskpilot.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.net.ntp.NTPUDPClient;
import org.apache.commons.net.ntp.TimeInfo;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.net.InetAddress;

@Slf4j
@Component
public class StartupRunner implements CommandLineRunner {

    private static final long MAX_CLOCK_DRIFT_MS = 2000L;

    @Override
    public void run(String... args) {
        if (!validateClockSync()) {
            log.error("Halting startup due to severe clock drift.");
            throw new IllegalStateException("CLOCK_DRIFT_LIMIT_EXCEEDED");
        }
    }

    private boolean validateClockSync() {
        NTPUDPClient client = new NTPUDPClient();
        try {
            client.setDefaultTimeout(2000);
            client.open();
            TimeInfo info = client.getTime(InetAddress.getByName("time.google.com"));
            info.computeDetails();
            Long offset = info.getOffset();
            if (offset == null) {
                log.warn("NTP check returned no offset - proceeding with caution");
                return true;
            }

            long driftMs = Math.abs(offset);
            if (driftMs > MAX_CLOCK_DRIFT_MS) {
                log.error("CLOCK DRIFT: {}ms exceeds {}ms limit", driftMs, MAX_CLOCK_DRIFT_MS);
                log.error("Sync the system clock with an NTP server before enabling trading.");
                return false;
            }
            log.info("Clock sync OK (drift: {}ms)", driftMs);
            return true;
        } catch (Exception e) {
            log.warn("NTP check failed: {} - proceeding with caution", e.getMessage());
            return true;
        } finally {
            client.close();
        }
    }
}
