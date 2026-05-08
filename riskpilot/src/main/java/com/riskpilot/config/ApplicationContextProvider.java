package com.riskpilot.config;

import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;

/** Provides Spring context access to non-managed classes */
@Component
public class ApplicationContextProvider implements ApplicationContextAware {
    private static ApplicationContext ctx;
    
    public void setApplicationContext(ApplicationContext ac) { ctx = ac; }
    
    public static <T> T getBean(Class<T> clazz) { return ctx.getBean(clazz); }
}