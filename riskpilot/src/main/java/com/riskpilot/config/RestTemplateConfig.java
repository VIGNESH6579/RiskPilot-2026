package com.riskpilot.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * RestTemplateConfig
 *
 * FIX: AngelSessionManager has @Autowired RestTemplate restTemplate,
 * but no RestTemplate @Bean existed anywhere in the codebase.
 * This caused NoSuchBeanDefinitionException at startup.
 *
 * All other services (AngelAuthService, AngelOneMarketDataService, etc.)
 * create their own RestTemplate instances internally, so this bean is
 * only picked up by AngelSessionManager.
 */
@Configuration
public class RestTemplateConfig {

    @Bean
    public RestTemplate restTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(10_000);
        return new RestTemplate(factory);
    }
}
