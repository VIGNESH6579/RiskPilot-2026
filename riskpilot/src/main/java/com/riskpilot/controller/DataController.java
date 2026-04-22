package com.riskpilot.controller;

import com.riskpilot.service.OptionChainService;
import com.riskpilot.service.VixService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/data")
@CrossOrigin
@RequiredArgsConstructor
public class DataController {

    private final VixService vixService;
    private final OptionChainService optionChainService;

    @GetMapping("/vix")
    public double getVix() {
        return vixService.getIndiaVix();
    }

    @GetMapping("/option-chain")
    public OptionChainService.OptionChainSnapshot getOptionChain() {
        return optionChainService.fetchNiftyChain();
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        OptionChainService.OptionChainSnapshot chain = optionChainService.fetchNiftyChain();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("optionChainSource", chain.source());
        payload.put("spot", chain.spot());
        payload.put("expiry", chain.expiry());
        payload.put("vix", vixService.getIndiaVix());
        payload.put("healthy", chain.spot() > 0.0);
        return payload;
    }
}
