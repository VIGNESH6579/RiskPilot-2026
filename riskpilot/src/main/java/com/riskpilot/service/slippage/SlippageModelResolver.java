package com.riskpilot.service.slippage;

import com.riskpilot.config.RiskPilotProperties;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Selects a {@link SlippageModel} based on
 * {@code riskpilot.execution.slippage.model}. Resolution happens once at
 * application startup so the live tick path never pays an indirection
 * cost.
 */
@Slf4j
@Component
public class SlippageModelResolver {

    private final RiskPilotProperties properties;
    private final FixedTickSlippageModel fixedTick;
    private final AtrFractionSlippageModel atrFraction;
    private SlippageModel active;

    public SlippageModelResolver(
        RiskPilotProperties properties,
        FixedTickSlippageModel fixedTick,
        AtrFractionSlippageModel atrFraction
    ) {
        this.properties = properties;
        this.fixedTick = fixedTick;
        this.atrFraction = atrFraction;
    }

    @PostConstruct
    public void resolve() {
        String configured = properties.getExecution().getSlippage().getModel();
        switch (configured == null ? "" : configured.trim().toUpperCase()) {
            case "FIXED_TICK" -> this.active = fixedTick;
            case "ATR_FRACTION", "" -> this.active = atrFraction;
            default -> {
                log.warn(
                    "Unknown slippage model '{}', defaulting to ATR_FRACTION. Valid: FIXED_TICK | ATR_FRACTION",
                    configured
                );
                this.active = atrFraction;
            }
        }
        log.info("Slippage model resolved to {}", active.name());
    }

    public SlippageModel active() {
        return active;
    }
}
