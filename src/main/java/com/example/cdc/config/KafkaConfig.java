package com.example.cdc.config;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;

import java.util.Map;

/**
 * <b>Kafka Consumer Configuration</b>
 *
 * <p>Provides {@link ConsumerFactory} and {@link ConcurrentKafkaListenerContainerFactory} beans
 * for the Kafka CDC consumer (Phase 2). Active only when the {@code kafka} profile is enabled.
 *
 * <pre>
 *  Kafka Topic ──> ConsumerFactory ──> KafkaListenerContainerFactory ──> @KafkaListener
 * </pre>
 */
@Configuration
@Profile("kafka")
public class KafkaConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Value("${spring.kafka.consumer.group-id}")
    private String groupId;

    /**
     * <b>Kafka Consumer Factory</b>
     *
     * <p>Creates a {@link DefaultKafkaConsumerFactory} configured with the bootstrap servers, consumer
     * group, and string deserializers for both key and value. Uses {@code auto.offset.reset=earliest}
     * so that newly deployed consumers read all existing CDC events from the beginning of each topic,
     * ensuring no events are missed after a restart or redeployment.
     *
     * @return a {@link ConsumerFactory} for {@code String} key-value Kafka records
     */
    @Bean
    public ConsumerFactory<String, String> consumerFactory() {
        return new DefaultKafkaConsumerFactory<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ConsumerConfig.GROUP_ID_CONFIG, groupId,
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"
        ));
    }

    /**
     * <b>Kafka Listener Container Factory</b>
     *
     * <p>Creates the {@code kafkaListenerContainerFactory} bean required by Spring's
     * {@link org.springframework.kafka.annotation.KafkaListener} annotation. This factory
     * manages the lifecycle of Kafka consumer threads — it creates one listener container per
     * {@code @KafkaListener} method, each polling its assigned topic for new CDC events.
     *
     * <p>Spring Boot 4.x does not auto-configure this bean, so it must be defined explicitly.
     *
     * @return a {@link ConcurrentKafkaListenerContainerFactory} wired with the consumer factory
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory());
        return factory;
    }
}
