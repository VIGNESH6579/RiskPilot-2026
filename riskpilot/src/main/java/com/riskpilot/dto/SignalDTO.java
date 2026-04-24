package com.riskpilot.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SignalDTO {
    
    @JsonProperty("id")
    private String id;
    
    @JsonProperty("signalTime")
    private String signalTime;
    
    @JsonProperty("executeTime")
    private String executeTime;
    
    @JsonProperty("expectedEntry")
    private Double expectedEntry;
    
    @JsonProperty("actualEntry")
    private Double actualEntry;
    
    @JsonProperty("slippage")
    private Double slippage;
    
    @JsonProperty("mfe")
    private Double mfe;
    
    @JsonProperty("mae")
    private Double mae;
    
    @JsonProperty("realizedR")
    private Double realizedR;
    
    @JsonProperty("isRunner")
    private Boolean isRunner;
    
    @JsonProperty("exitReason")
    private String exitReason;
    
    @JsonProperty("exitTime")
    private String exitTime;
    
    @JsonProperty("latencySec")
    private Double latencySec;
    
    @JsonProperty("observerTs")
    private Long observerTs;
}
