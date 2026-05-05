package com.riskpilot.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.*;
import java.util.Map;

@Service
public class VixService {
    @Value("${RISK_API_KEY}") private String apiKey;
    @Value("${RISK_INDIA_VIX_TOKEN}") private String vixToken;
    private final RestTemplate restTemplate = new RestTemplate();
    private Double lastVix = null;
    private long lastFetch = 0;

    public double getIndiaVix() {
        if (System.currentTimeMillis() - lastFetch < 60000 && lastVix != null) return lastVix;
        
        try {
            String url = "https://apiconnect.angelbroking.com/rest/secure/angelbroking/market/v1/quote/?symboltoken=" + vixToken + "&exchange=NSE";
            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Bearer " + apiKey);
            Map resp = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), Map.class).getBody();
            lastVix = Double.parseDouble(((Map) resp.get("data")).get("ltp").toString());
            lastFetch = System.currentTimeMillis();
            return lastVix;
        } catch (Exception e) {
            throw new RuntimeException("CRITICAL: VIX API Failed. No fallback allowed.");
        }
    }
}
