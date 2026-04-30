package com.riskpilot.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Startup invariant check that prevents the application from booting
 * into LIVE order-placement mode unless an actual broker order client
 * is wired into the context.
 *
 * <p>RiskPilot ships in SHADOW mode by default. The audit explicitly
 * scoped real-order placement out of bucket C, so the only currently
 * supported runtime is SHADOW. If the {@code riskpilot.mode} property
 * is set to {@code LIVE} but no real broker bean is present, we fail
 * fast with a clear error rather than running the engine against a
 * stub that silently swallows orders.
 *
 * <p>When a real broker client is added later, it should be a
 * {@link RealOrderClient} bean and this guard will permit LIVE mode
 * automatically. Until then, attempts to switch to LIVE produce a
 * loud, immediate startup failure.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BrokerSafetyGuard {

    private final RiskPilotProperties properties;
    private final org.springframework.context.ApplicationContext applicationContext;

    @PostConstruct
    public void verifyBrokerWiring() {
        if (!properties.isLiveMode()) {
            log.info(
                "Broker safety guard: mode={} (SHADOW). No real orders will be placed.",
                properties.getMode()
            );
            return;
        }

        boolean realBrokerWired = !applicationContext.getBeansOfType(RealOrderClient.class).isEmpty();
        if (!realBrokerWired) {
            String msg = "FATAL: riskpilot.mode=LIVE but no RealOrderClient bean is wired. "
                + "Real-order placement is intentionally not implemented yet (audit bucket C "
                + "kept the engine in shadow mode). Set RISKPILOT_MODE=SHADOW to start.";
            log.error(msg);
            throw new IllegalStateException(msg);
        }

        log.warn(
            "Broker safety guard: mode=LIVE with RealOrderClient wired. "
            + "REAL ORDERS WILL BE PLACED. Make sure this was intentional."
        );
    }

    /**
     * Marker interface for a future real-broker order client. No real
     * implementation exists in this PR — the engine is shadow-only by
     * design. This type is here purely so {@link BrokerSafetyGuard} can
     * detect when a real client is eventually wired.
     */
    public interface RealOrderClient {
        // intentionally empty — the broker contract will be defined when
        // a real implementation is introduced.
    }
}
