package com.example.cdc.consumer;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.stream.StreamListener;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.data.redis.stream.Subscription;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * <b>Redis Stream Consumer (Phase 1)</b>
 *
 * <p>Listens on Redis Streams populated by Debezium Server's Redis Sink. Each stream
 * corresponds to a PostgreSQL table ({@code cdc_demo.public.users},
 * {@code cdc_demo.public.orders}). Delegates event processing to
 * {@link CdcEventTransformer}.
 *
 * <pre>
 *  Debezium Server ──> Redis Streams ──> RedisStreamConsumer ──> CdcEventTransformer ──> Redis Keys
 * </pre>
 *
 * <p>Active only when {@code debezium-server} profile is enabled.
 */
@Slf4j
@Component
@Profile("debezium-server")
@RequiredArgsConstructor
public class RedisStreamConsumer implements StreamListener<String, MapRecord<String, String, String>> {

    private final RedisTemplate<String, String> redisTemplate;
    private final CdcEventTransformer cdcEventTransformer;

    @Value("${app.cdc.redis-stream.users-stream}")
    private String usersStream;

    @Value("${app.cdc.redis-stream.orders-stream}")
    private String ordersStream;

    @Value("${app.cdc.redis-stream.consumer-group}")
    private String consumerGroup;

    @Value("${app.cdc.redis-stream.consumer-name}")
    private String consumerName;

    private StreamMessageListenerContainer<String, MapRecord<String, String, String>> container;
    private Subscription usersSubscription;
    private Subscription ordersSubscription;

    @PostConstruct
    public void start() {
        log.info("Starting Redis Stream CDC consumer (Phase 1)");
        createConsumerGroupIfNotExists(usersStream);
        createConsumerGroupIfNotExists(ordersStream);

        var options = StreamMessageListenerContainer.StreamMessageListenerContainerOptions
                .builder()
                .pollTimeout(Duration.ofSeconds(2))
                .build();

        container = StreamMessageListenerContainer.create(
                redisTemplate.getConnectionFactory(), options);

        usersSubscription = container.receive(
                Consumer.from(consumerGroup, consumerName),
                StreamOffset.create(usersStream, ReadOffset.lastConsumed()),
                this);

        ordersSubscription = container.receive(
                Consumer.from(consumerGroup, consumerName),
                StreamOffset.create(ordersStream, ReadOffset.lastConsumed()),
                this);

        container.start();
        log.info("Redis Stream consumer started — listening on streams: {}, {}",
                usersStream, ordersStream);
    }

    @PreDestroy
    public void stop() {
        if (container != null) {
            container.stop();
            log.info("Redis Stream consumer stopped");
        }
    }

    @Override
    public void onMessage(MapRecord<String, String, String> message) {
        try {
            String streamKey = message.getStream();
            String eventJson = message.getValue().values().iterator().next();
            log.debug("Received CDC event from stream={} id={}", streamKey, message.getId());
            cdcEventTransformer.processEvent(eventJson);
            redisTemplate.opsForStream().acknowledge(consumerGroup, message);
        } catch (Exception e) {
            log.error("Failed to process CDC event: {}", message.getId(), e);
        }
    }

    private void createConsumerGroupIfNotExists(String streamKey) {
        try {
            redisTemplate.opsForStream().createGroup(streamKey, consumerGroup);
            log.info("Created consumer group '{}' for stream '{}'", consumerGroup, streamKey);
        } catch (Exception e) {
            log.debug("Consumer group '{}' may already exist for stream '{}': {}",
                    consumerGroup, streamKey, e.getMessage());
        }
    }
}
