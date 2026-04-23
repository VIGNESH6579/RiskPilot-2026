package com.riskpilot.exception;

public class RiskPilotException extends RuntimeException {
    
    public RiskPilotException(String message) {
        super(message);
    }
    
    public RiskPilotException(String message, Throwable cause) {
        super(message, cause);
    }
}
