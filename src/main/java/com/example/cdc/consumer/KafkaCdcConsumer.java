package com.example.cdc.consumer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * <b>Kafka CDC Consumer (Phase 2)</b>
 *
 * <p>Listens on Kafka topics populated by Debezium Kafka Connect. Each topic corresponds
 * to a PostgreSQL table. Delegates event processing to {@link CdcEventTransformer}.
 *
 * <pre>
 *  Debezium Connect ──> Kafka ──> KafkaCdcConsumer ──> CdcEventTransformer ──> Redis Keys
 * </pre>
 *
 * <p>Active only when {@code kafka} profile is enabled.
 */
@Slf4j
@Component
@Profile("kafka")
@RequiredArgsConstructor
public class KafkaCdcConsumer {

    private final CdcEventTransformer cdcEventTransformer;

    @KafkaListener(topics = "${app.cdc.kafka.users-topic}", groupId = "cdc-demo-group")
    public void consumeUserEvent(String eventJson) {
        log.debug("Received user CDC event from Kafka");
        cdcEventTransformer.processEvent(eventJson);
    }

    @KafkaListener(topics = "${app.cdc.kafka.orders-topic}", groupId = "cdc-demo-group")
    public void consumeOrderEvent(String eventJson) {
        log.debug("Received order CDC event from Kafka");
        cdcEventTransformer.processEvent(eventJson);
    }
}
