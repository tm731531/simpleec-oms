package com.simpleec.schedulerjob.consumer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.simpleec.common.constants.TopicConstants;
import com.simpleec.common.enums.TaskTypeEnum;
import com.simpleec.common.util.NanoIdUtil;
import com.simpleec.common.util.DateUtil;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Manual Kafka Consumer - 直接使用 KafkaConsumer 避免 Spring Kafka 的 partition assignment 問題
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ManualSchedulerConsumer {
    
    private final KafkaProperties kafkaProperties;
    private final ObjectMapper objectMapper;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private KafkaConsumer<String, String> consumer;
    private volatile boolean running = false;
    private Thread consumerThread;
    
    @PostConstruct
    public void start() {
        log.info("🚀 Starting ManualSchedulerConsumer...");
        Map<String, Object> consumerProps = new HashMap<>();
        consumerProps.put("bootstrap.servers", String.join(",", kafkaProperties.getBootstrapServers()));
        consumerProps.put("group.id", "manual-scheduler-dispatcher-v1");
        consumerProps.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        consumerProps.put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        consumerProps.put("auto.offset.reset", "earliest");
        consumerProps.put("enable.auto.commit", "true");
        consumerProps.put("auto.commit.interval.ms", "5000");
        
        consumer = new KafkaConsumer<>(consumerProps);
        consumer.subscribe(Arrays.asList("scheduler"));
        log.info("✅ Consumer subscribed to 'scheduler' topic");
        
        running = true;
        consumerThread = new Thread(() -> pollAndProcess(), "ManualSchedulerConsumerThread");
        consumerThread.setDaemon(false);
        consumerThread.start();
        log.info("✅ Consumer thread started");
    }
    
    private void pollAndProcess() {
        int emptyPollCount = 0;
        while (running) {
            try {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(5));
                
                if (records.isEmpty()) {
                    emptyPollCount++;
                    if (emptyPollCount % 5 == 0) {
                        log.debug("Manual consumer poll returned 0 records ({} consecutive empty polls)", emptyPollCount);
                    }
                } else {
                    emptyPollCount = 0;
                    log.info("🎉 Received {} records from scheduler topic", records.count());
                    
                    for (ConsumerRecord<String, String> record : records) {
                        try {
                            log.warn("🔥🔥🔥 Processing message: {} (offset: {})", 
                                record.value().substring(0, Math.min(50, record.value().length())),
                                record.offset());
                            processMessage(record.value());
                        } catch (Exception e) {
                            log.error("Error processing message at offset {}", record.offset(), e);
                        }
                    }
                }
            } catch (Exception e) {
                log.error("Error polling Kafka", e);
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }
    
    private void processMessage(String message) {
        try {
            JsonNode json = objectMapper.readTree(message);
            JsonNode body = json.get("body");
            
            long timestamp = body.get("timestamp").asLong();
            int minuteOfHour = body.get("minuteOfHour").asInt();
            
            int mod5 = minuteOfHour % 5;
            
            if (mod5 == 0) {
                log.info("Dispatching FETCH_ORDERS at minute {}", minuteOfHour);
                dispatchReport(TaskTypeEnum.FETCH_ORDERS, timestamp);
            } else if (mod5 == 1) {
                log.info("Dispatching ORDER_REPORT at minute {}", minuteOfHour);
                dispatchReport(TaskTypeEnum.ORDER_REPORT, timestamp);
            } else if (mod5 == 2) {
                log.info("Dispatching INVENTORY_REPORT at minute {}", minuteOfHour);
                dispatchReport(TaskTypeEnum.INVENTORY_REPORT, timestamp);
            } else if (mod5 == 3) {
                log.info("Dispatching SALES_REPORT at minute {}", minuteOfHour);
                dispatchReport(TaskTypeEnum.SALES_REPORT, timestamp);
            } else if (mod5 == 4) {
                log.info("Dispatching RETURN_REPORT at minute {}", minuteOfHour);
                dispatchReport(TaskTypeEnum.RETURN_REPORT, timestamp);
            }
        } catch (Exception e) {
            log.error("Error processing message", e);
        }
    }
    
    private void dispatchReport(TaskTypeEnum reportType, long timestamp) {
        try {
            ObjectNode message = objectMapper.createObjectNode();
            ObjectNode header = objectMapper.createObjectNode();
            header.put("messageId", NanoIdUtil.generate());
            header.put("taskType", reportType.getCode());
            header.put("timestamp", DateUtil.toIsoString(timestamp));
            header.put("version", "1.0");
            
            ObjectNode body = objectMapper.createObjectNode();
            body.put("timeRange", "Last 5 minutes");
            body.put("merchantId", "MERCHANT_001");
            
            message.set("header", header);
            message.set("body", body);
            
            kafkaTemplate.send(TopicConstants.TASK_BACKEND, header.get("messageId").asText(), message);
            log.info("✅ Dispatched {} to task.backend", reportType.getCode());
        } catch (Exception e) {
            log.error("Error dispatching {}", reportType.getCode(), e);
        }
    }
    
    public void stop() {
        log.info("Stopping ManualSchedulerConsumer...");
        running = false;
        if (consumer != null) {
            consumer.close();
        }
        try {
            if (consumerThread != null) {
                consumerThread.join(10000);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        log.info("ManualSchedulerConsumer stopped");
    }
}
