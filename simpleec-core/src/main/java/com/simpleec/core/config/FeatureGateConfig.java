package com.simpleec.core.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "feature.gates")
@Data
public class FeatureGateConfig {
    private boolean enhancedRetryLogic = false;
}
