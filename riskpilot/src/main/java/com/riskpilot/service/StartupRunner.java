package com.riskpilot.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.net.ntp.NTPUDPClient;
import org.apache.commons.net.ntp.TimeInfo;
import org.springframework.boot.CommandLineRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

@Slf4j
@Component
public class StartupRunner implements CommandLineRunner {
    private static final long MAX_CLOCK_DRIFT_MS = 2_000L;

    /**
     * RENDER DB EXPIRY REMINDER
     * Free-tier Render PostgreSQL databases expire after 90 days.
     * IMPORTANT: The original DB (dpg-d7s4463t6lks73c50eog) expired July 18 2025.
     * If you are still using that DB, ALL historical data is gone — provision a new one NOW.
     *
     * Current tracked expiry: update DB_EXPIRY_DATE below whenever you provision a new DB.
     * Formula: provisioning date + 90 days.
     *
     * When it expires, ALL trades, candles, and session state are permanently lost.
     * Action before expiry:
     *   1. pg_dump the current DB
     *   2. Create a new Render PostgreSQL (free tier resets the 90-day clock)
     *   3. pg_restore into the new DB
     *   4. Update DATABASE_URL env var on the Render web service
     *   5. Update DB_EXPIRY_DATE below and redeploy
     *
     * This warning fires every startup AND daily so you can't miss it.
     */
    // ⚠️ UPDATE THIS DATE whenever you provision a new Render DB (provisioning_date + 90 days)
    private static final LocalDate DB_EXPIRY_DATE = LocalDate.of(2025, 8, 17); // dpg-d7s4463t6lks73c50eog EXPIRED — update when new DB is provisioned
    private static final int DB_WARN_DAYS_BEFORE = 14;

    @Override
    public void run(String... args) {
        validateClockSync();
        warnDbExpiry();
    }

    @Scheduled(fixedDelay = 21_600_000L) // every 6 hours
    public void validateClockSyncPeriodically() {
        validateClockSync();
    }

    @Scheduled(cron = "0 0 8 * * *", zone = "Asia/Kolkata") // daily at 8 AM IST
    public void warnDbExpiryDaily() {
        warnDbExpiry();
    }

    private void warnDbExpiry() {
        long daysUntilExpiry = ChronoUnit.DAYS.between(LocalDate.now(), DB_EXPIRY_DATE);
        if (daysUntilExpiry <= 0) {
            log.error("🚨🚨🚨 RENDER DB EXPIRED ({}) 🚨🚨🚨 — All data is permanently lost. "
                + "Provision a new DB and update DATABASE_URL immediately!", DB_EXPIRY_DATE);
        } else if (daysUntilExpiry <= DB_WARN_DAYS_BEFORE) {
            log.warn("⚠️ RENDER DB EXPIRES IN {} DAYS ({}) — Backup data NOW and create a new DB before expiry!",
                daysUntilExpiry, DB_EXPIRY_DATE);
        } else {
            log.info("✅ Render DB expiry check: {} days remaining (expires {})", daysUntilExpiry, DB_EXPIRY_DATE);
        }
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
