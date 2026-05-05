package com.riskpilot.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import javax.annotation.PostConstruct;
import java.time.*;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class MarketSessionService {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    
    // Cache holidays by year
    private final ConcurrentHashMap<Integer, Set<LocalDate>> holidayCache = new ConcurrentHashMap<>();
    
    // Market timings (IST)
    private static final LocalTime MARKET_OPEN = LocalTime.of(9, 15);
    private static final LocalTime MARKET_CLOSE = LocalTime.of(15, 30);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    public MarketSessionService(RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void initialize() {
        loadHolidays(Year.now().getValue());
        loadHolidays(Year.now().getValue() + 1); // Preload next year
    }

    /**
     * Refresh holidays every day at 1 AM IST
     */
    @Scheduled(cron = "0 0 1 * * *", zone = "Asia/Kolkata")
    public void refreshHolidays() {
        int currentYear = Year.now().getValue();
        log.info("Refreshing holiday calendar for year {}", currentYear);
        holidayCache.remove(currentYear);
        loadHolidays(currentYear);
    }

    private void loadHolidays(int year) {
        try {
            // NSE Holiday Calendar API
            String url = String.format(
                "https://www.nseindia.com/api/holiday-master?type=trading&year=%d",
                year
            );
            
            var headers = new org.springframework.http.HttpHeaders();
            headers.set("User-Agent", "Mozilla/5.0");
            headers.set("Accept", "application/json");
            
            var entity = new org.springframework.http.HttpEntity<>(headers);
            var response = restTemplate.exchange(
                url,
                org.springframework.http.HttpMethod.GET,
                entity,
                String.class
            );
            
            Set<LocalDate> holidays = parseHolidays(response.getBody());
            holidayCache.put(year, holidays);
            
            log.info("Loaded {} holidays for year {}", holidays.size(), year);
            
        } catch (Exception e) {
            log.error("Failed to load holidays from NSE API for year " + year, e);
            
            // Fallback: use minimal guaranteed holidays
            Set<LocalDate> fallbackHolidays = Set.of(
                LocalDate.of(year, 1, 26),  // Republic Day
                LocalDate.of(year, 8, 15),  // Independence Day
                LocalDate.of(year, 10, 2)   // Gandhi Jayanti
            );
            holidayCache.put(year, fallbackHolidays);
            log.warn("Using fallback holidays for year {}", year);
        }
    }

    private Set<LocalDate> parseHolidays(String json) {
        try {
            var data = objectMapper.readValue(json, java.util.Map.class);
            var cm = (java.util.List
