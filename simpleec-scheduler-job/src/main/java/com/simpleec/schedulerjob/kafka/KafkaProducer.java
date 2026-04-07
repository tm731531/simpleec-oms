package com.simpleec.schedulerjob.kafka;

import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Kafka producer for publishing messages
 * Note: Kafka is configured with JsonSerializer, so we send Objects directly
 */
@Slf4j
@Component
public class KafkaProducer {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public KafkaProducer(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    /**
     * Publish message to Kafka topic
     * Note: Kafka is configured with JsonSerializer, so we send the Object directly
     */
    public void publishToTopic(String topic, String key, Object message) {
        try {
            // JsonSerializer will serialize the ObjectNode to JSON
            kafkaTemplate.send(topic, key, message);
            log.debug("Published message to topic {} with key {}", topic, key);
        } catch (Exception e) {
            log.error("Error publishing message to topic {}", topic, e);
            throw new RuntimeException("Failed to publish message", e);
        }
    }
}
