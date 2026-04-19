package com.riskpilot.service;

import com.riskpilot.dto.OptionLevels;
import com.riskpilot.model.OptionData;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class OptionChainService {

    private final MarketService marketService;

    public OptionChainService(MarketService marketService) {
        this.marketService = marketService;
    }

    public OptionLevels analyzeOptionChain(String symbol) {

        // 🔴 Replace this with real API later
        List<OptionData> chain = marketService.getMockOptionChain();

        double maxCallOi = 0;
        double maxPutOi = 0;

        double resistance = 0;
        double support = 0;

        double totalCallOi = 0;
        double totalPutOi = 0;

        for (OptionData data : chain) {

            totalCallOi += data.getCallOi();
            totalPutOi += data.getPutOi();

            if (data.getCallOi() > maxCallOi) {
                maxCallOi = data.getCallOi();
                resistance = data.getStrike();
            }

            if (data.getPutOi() > maxPutOi) {
                maxPutOi = data.getPutOi();
                support = data.getStrike();
            }
        }

        double pcr = totalPutOi / (totalCallOi == 0 ? 1 : totalCallOi);

        OptionLevels levels = new OptionLevels();
        levels.setResistance(resistance);
        levels.setSupport(support);
        levels.setPcr(pcr);

        return levels;
    }
}