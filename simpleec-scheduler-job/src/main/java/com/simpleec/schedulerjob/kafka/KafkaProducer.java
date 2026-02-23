package com.simpleec.schedulerjob.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Kafka producer for publishing messages
 */
@Slf4j
@Component
public class KafkaProducer {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public KafkaProducer(KafkaTemplate<String, String> kafkaTemplate,
                        ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * Publish message to Kafka topic
     */
    public void publishToTopic(String topic, String key, Object message) {
        try {
            String payload = objectMapper.writeValueAsString(message);
            kafkaTemplate.send(topic, key, payload);
            log.debug("Published message to topic {} with key {}", topic, key);
        } catch (Exception e) {
            log.error("Error publishing message to topic {}", topic, e);
            throw new RuntimeException("Failed to publish message", e);
        }
    }
}
