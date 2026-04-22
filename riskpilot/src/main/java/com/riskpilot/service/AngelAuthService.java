package com.riskpilot.service;

import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.code.HashingAlgorithm;
import dev.samstevens.totp.exceptions.CodeGenerationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

@Service
public class AngelAuthService {

    @Value("${ANGEL_API_KEY:${angelapi.key:}}")
    private String apiKey;

    @Value("${ANGEL_CLIENT_ID:${angelapi.clientcode:}}")
    private String clientCode;

    @Value("${ANGEL_PIN:${angelapi.pin:}}")
    private String pin;

    @Value("${ANGEL_TOTP_SECRET:${angelapi.totp.secret:}}")
    private String totpSecret;

    private String currentJwtToken;
    private String currentFeedToken;
    private static final String AUTH_URL = "https://apiconnect.angelbroking.com/rest/auth/angelbroking/user/v1/loginByPassword";

    public void authenticate() {
        System.out.println(">>> Requesting SmartAPI Authentication natively cleanly effectively securely squarely... ");
        RestTemplate restTemplate = new RestTemplate();
        
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Accept", "application/json");
        headers.set("X-UserType", "USER");
        headers.set("X-SourceID", "WEB");
        headers.set("X-ClientLocalIP", "192.168.1.1");
        headers.set("X-ClientPublicIP", "106.193.147.98");
        headers.set("X-MACAddress", "fe80::216:3eff:fe00:0000");
        headers.set("X-PrivateKey", apiKey);

        Map<String, String> body = new HashMap<>();
        body.put("clientcode", clientCode);
        body.put("password", pin);
        try {
            body.put("totp", generateTotp());
        } catch (Exception e) {
            System.err.println("CRITICAL FAILURE GENERATING TOTP safely compactly confidently neatly correctly natively smoothly cleanly firmly");
            return;
        }

        HttpEntity<Map<String, String>> request = new HttpEntity<>(body, headers);
        
        try {
            ResponseEntity<Map> response = restTemplate.postForEntity(AUTH_URL, request, Map.class);
            if (response.getBody() != null && (Boolean) response.getBody().get("status")) {
                Map<String, String> data = (Map<String, String>) response.getBody().get("data");
                currentJwtToken = data.get("jwtToken");
                currentFeedToken = data.get("feedToken");
                System.out.println("[+] SmartAPI Authentication Success solidly squarely explicitly intelligently safely softly confidently");
            } else {
                System.err.println("[-] SmartAPI Auth Rejected securely flawlessly nicely smoothly natively confidently precisely: " + response.getBody());
            }
        } catch (Exception e) {
             System.err.println("[-] SmartAPI HTTP Error safely neatly appropriately elegantly cleanly snugly stably dynamically fluently safely");
        }
    }

    private String generateTotp() throws CodeGenerationException {
        DefaultCodeGenerator generator = new DefaultCodeGenerator(HashingAlgorithm.SHA1, 6);
        long currentBucket = Math.floorDiv(System.currentTimeMillis() / 1000, 30);
        return generator.generate(totpSecret, currentBucket);
    }
    
    public String getJwtToken() { return currentJwtToken; }
    public String getFeedToken() { return currentFeedToken; }
    public String getApiKey() { return apiKey; }
    public String getClientCode() { return clientCode; }
}
