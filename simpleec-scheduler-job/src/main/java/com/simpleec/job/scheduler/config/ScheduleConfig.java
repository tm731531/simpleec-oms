package com.simpleec.job.scheduler.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

@Data
@Configuration
@ConfigurationProperties(prefix = "schedule")
public class ScheduleConfig {
    private List<ScheduleRule> rules = new ArrayList<>();
}
