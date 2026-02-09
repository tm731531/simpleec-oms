package com.simpleec.job.scheduler.config;

import lombok.Data;

@Data
public class ScheduleRule {
    private String id;
    private String action;
    private String mode;           // "interval" or "cron"
    private int intervalSeconds;
    private String cronExpression;
    private String timezone;
    private int minGapSeconds;
    private String merchantId;
}
