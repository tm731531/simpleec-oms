package com.simpleec.core.kafka;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.config.TopicConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.*;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.util.backoff.FixedBackOff;
import org.apache.kafka.clients.admin.NewTopic;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;

@Configuration
@Slf4j
public class KafkaConfig {

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    // ===== Per-channel Topics (8 partitions each) =====
    @Bean public NewTopic momoFast()    { return channelTopic("momo.fast"); }
    @Bean public NewTopic momoSlow()    { return channelTopic("momo.slow"); }
    @Bean public NewTopic shopeeFast()  { return channelTopic("shopee.fast"); }
    @Bean public NewTopic shopeeSlow()  { return channelTopic("shopee.slow"); }
    @Bean public NewTopic yahooFast()   { return channelTopic("yahoo.fast"); }
    @Bean public NewTopic yahooSlow()   { return channelTopic("yahoo.slow"); }
    @Bean public NewTopic pchomeFast()  { return channelTopic("pchome.fast"); }
    @Bean public NewTopic pchomeSlow()  { return channelTopic("pchome.slow"); }

    private NewTopic channelTopic(String name) {
        return TopicBuilder.name(name)
            .partitions(8)
            .replicas(1)
            .config(TopicConfig.RETENTION_MS_CONFIG, "-1")
            .build();
    }

    // ===== Business Topics (8 partitions) =====
    @Bean
    public NewTopic orderProcess() {
        return TopicBuilder.name("order.process")
            .partitions(8).replicas(1)
            .config(TopicConfig.RETENTION_MS_CONFIG, "-1")
            .build();
    }

    @Bean
    public NewTopic taskBackend() {
        return TopicBuilder.name("task.backend")
            .partitions(8).replicas(1)
            .config(TopicConfig.RETENTION_MS_CONFIG, "-1")
            .build();
    }

    @Bean
    public NewTopic taskFrontend() {
        return TopicBuilder.name("task.frontend")
            .partitions(8).replicas(1)
            .config(TopicConfig.RETENTION_MS_CONFIG, "-1")
            .build();
    }

    // ===== Scheduler Topic (4 partitions) =====
    @Bean
    public NewTopic scheduler() {
        return TopicBuilder.name("scheduler")
            .partitions(4).replicas(1)
            .config(TopicConfig.RETENTION_MS_CONFIG, "-1")
            .build();
    }

    // ===== Failed Topic (4 partitions) =====
    @Bean
    public NewTopic taskFailed() {
        return TopicBuilder.name("task.failed")
            .partitions(4).replicas(1)
            .config(TopicConfig.RETENTION_MS_CONFIG, "-1")
            .build();
    }

    // ===== Dead Letter Topic (30 day retention) =====
    @Bean
    public NewTopic taskDlt() {
        return TopicBuilder.name("task.dlt")
            .partitions(4).replicas(1)
            .config(TopicConfig.RETENTION_MS_CONFIG,
                    String.valueOf(30L * 24 * 60 * 60 * 1000))
            .build();
    }

    // ===== Producer Factory =====
    @Bean
    public ProducerFactory<String, TaskMessage> producerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.RETRIES_CONFIG, 3);
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        return new DefaultKafkaProducerFactory<>(props);
    }

    @Bean
    public KafkaTemplate<String, TaskMessage> kafkaTemplate() {
        return new KafkaTemplate<>(producerFactory());
    }

    // ===== Consumer Factory =====
    @Bean
    public ConsumerFactory<String, TaskMessage> consumerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 10);
        props.put(JsonDeserializer.TRUSTED_PACKAGES, "com.simpleec.*");
        return new DefaultKafkaConsumerFactory<>(props);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, TaskMessage> kafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, TaskMessage> factory =
            new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory());
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);

        // Poison pill protection: skip undeserializable messages instead of blocking
        factory.setCommonErrorHandler(new DefaultErrorHandler(
            (record, ex) -> log.error("Poison pill detected: topic={}, partition={}, offset={}, error={}",
                record.topic(), record.partition(), record.offset(), ex.getMessage()),
            new FixedBackOff(0L, 0L)  // No retries for deserialization errors
        ));

        return factory;
    }
}
