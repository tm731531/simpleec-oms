package com.simpleec.core.kafka;

import io.opentelemetry.api.trace.Span;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class TaskProducer {

    private final KafkaTemplate<String, TaskMessage> kafka;

    public void send(String topic, String key, TaskMessage msg) {
        // Stamp current W3C trace-id into message for business-level correlation
        try {
            Span current = Span.current();
            if (current.getSpanContext().isValid()) {
                msg.setTraceId(current.getSpanContext().getTraceId());
            }
        } catch (Exception e) {
            // OTEL API not available (e.g., no agent attached) — skip gracefully
            log.trace("OTEL trace-id injection skipped: {}", e.getMessage());
        }

        kafka.send(topic, key, msg)
            .whenComplete((result, ex) -> {
                if (ex != null) {
                    log.error("Kafka send failed: topic={}, key={}, error={}",
                        topic, key, ex.getMessage());
                } else {
                    log.debug("Kafka sent: topic={}, partition={}, offset={}",
                        topic,
                        result.getRecordMetadata().partition(),
                        result.getRecordMetadata().offset());
                }
            });
    }
}
