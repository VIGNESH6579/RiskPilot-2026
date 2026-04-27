package com.riskpilot.service;

import com.riskpilot.config.RiskPilotProperties;
import com.riskpilot.model.ActiveTradeExecution;
import com.riskpilot.model.Signal;
import com.riskpilot.model.TradeExit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Service
@RequiredArgsConstructor
public class NtfyNotificationService {

    private static final String BASE_URL = "https://ntfy.sh/";

    private final RiskPilotProperties properties;
    private final RestTemplate restTemplate = new RestTemplate();
    private final AtomicLong lastNotificationEpochMs = new AtomicLong(0L);

    public void notifyTradeEntry(Signal signal, ActiveTradeExecution trade) {
        String title = "RiskPilot Entry";
        String body = String.format(
            "NIFTY %s entry %.2f | SL %.2f | TP1 %.2f | Qty %d",
            signal.getDirection(),
            trade.entryPrice(),
            trade.stopLoss(),
            trade.tp1Level(),
            trade.quantity()
        );
        publish(title, body, "chart_with_upwards_trend,rotating_light");
    }

    public void notifyTradeExit(ActiveTradeExecution trade, TradeExit exit, double realizedR) {
        String direction = trade.tp1Level() < trade.entryPrice() ? "SHORT" : "LONG";
        String title = "RiskPilot Exit";
        String body = String.format(
            "NIFTY %s exit %.2f | Reason %s | Realized R %.2f | Qty %d",
            direction,
            exit.exitPrice(),
            exit.reason(),
            realizedR,
            trade.quantity()
        );
        publish(title, body, "white_check_mark,bar_chart");
    }

    private void publish(String title, String body, String tags) {
        RiskPilotProperties.Notification notification = properties.getNotification();
        if (notification == null || !notification.isNtfyEnabled()) {
            return;
        }

        long now = Instant.now().toEpochMilli();
        long minGapMs = Math.max(0L, notification.getNtfyCooldownSec()) * 1000L;
        long lastSent = lastNotificationEpochMs.get();
        if (now - lastSent < minGapMs) {
            return;
        }

        String topic = notification.getNtfyTopic();
        if (topic == null || topic.isBlank()) {
            return;
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_PLAIN);
        headers.set("Title", title);
        headers.set("Tags", tags);
        headers.set("Priority", "default");

        try {
            restTemplate.postForEntity(BASE_URL + topic.trim(), new HttpEntity<>(body, headers), String.class);
            lastNotificationEpochMs.set(now);
        } catch (Exception e) {
            log.warn("ntfy publish failed: {}", e.getMessage());
        }
    }
}
