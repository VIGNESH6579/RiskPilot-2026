package com.riskpilot.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.*;

import javax.annotation.PostConstruct;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class MarketSessionService {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<Integer, Set<LocalDate>> holidayCache = new ConcurrentHashMap<>();
    
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalTime OPEN = LocalTime.of(9, 15);
    private static final LocalTime CLOSE = LocalTime.of(15, 30);

    public MarketSessionService(RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() {
        refreshHolidays();
    }

    @Scheduled(cron = "0 0 1 1 1 *", zone = "Asia/Kolkata") // Jan 1st yearly
    public void refreshHolidays() {
        int year = LocalDate.now(IST).getYear();
        try {
            String url = "https://www.nseindia.com/api/holiday-master?type=trading";
            HttpHeaders headers = new HttpHeaders();
            headers.set("User-Agent", "Mozilla/5.0");
            
            ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
            List<Map<String, Object>> cmList = (List<Map<String, Object>>) response.getBody().get("CM");
            
            Set<LocalDate> dates = new HashSet<>();
            for (Map<String, Object> day : cmList) {
                dates.add(LocalDate.parse((String) day.get("tradingDate"), DateTimeFormatter.ofPattern("dd-MMM-yyyy")));
            }
            holidayCache.put(year, dates);
            log.info("✅ Holidays updated for {}", year);
        } catch (Exception e) {
            log.error("❌ Failed to fetch dynamic holidays, check NSE connectivity", e);
        }
    }

    public boolean isMarketOpen() {
        ZonedDateTime now = ZonedDateTime.now(IST);
        DayOfWeek dow = now.getDayOfWeek();
        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) return false;
        if (holidayCache.getOrDefault(now.getYear(), Collections.emptySet()).contains(now.toLocalDate())) return false;
        LocalTime time = now.toLocalTime();
        return !time.isBefore(OPEN) && time.isBefore(CLOSE);
    }
}
