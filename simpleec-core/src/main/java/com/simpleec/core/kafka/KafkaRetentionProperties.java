package com.simpleec.core.kafka;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
@ConfigurationProperties(prefix = "simpleec.kafka.retention")
@Data
public class KafkaRetentionProperties {

    /** Retention for {platform}.fast and {platform}.slow topics */
    private Duration channel = Duration.ofDays(1);

    /** Retention for order.process topic */
    private Duration orderProcess = Duration.ofDays(1);

    /** Retention for task.backend topic */
    private Duration taskBackend = Duration.ofDays(1);

    /** Retention for task.frontend topic */
    private Duration taskFrontend = Duration.ofDays(1);

    /** Retention for scheduler topic */
    private Duration scheduler = Duration.ofDays(1);

    /** Retention for task.failed topic */
    private Duration taskFailed = Duration.ofDays(1);

    /** Retention for task.dlt (dead letter) topic */
    private Duration taskDlt = Duration.ofDays(30);
}
