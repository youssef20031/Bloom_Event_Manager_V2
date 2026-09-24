package com.bloom.eventmanager.knowledge.kafka;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

import com.bloom.eventmanager.events.BloomTopics;

@Configuration
public class KafkaConfig {

    /** knowledge-service owns this topic: it is the only producer. */
    @Bean
    NewTopic documentIndexedTopic() {
        return TopicBuilder.name(BloomTopics.DOCUMENT_INDEXED).partitions(3).replicas(1).build();
    }

    /*
     * The two topics below are consumed here and produced elsewhere, but they are
     * declared anyway, because whoever touches the topic first decides its shape.
     * If this service subscribes before the producer has started, the broker
     * auto-creates the topic with its default single partition; the producer's
     * later NewTopic raises it to three, and this consumer keeps its one-partition
     * view until it restarts — silently skipping two thirds of every batch.
     * Observed in Phase 3a verification, not hypothetical. Declaring the same
     * shape on both sides is idempotent and removes the ordering race.
     */

    @Bean
    NewTopic chunkBatchReadyTopic() {
        return TopicBuilder.name(BloomTopics.CHUNK_BATCH_READY).partitions(3).replicas(1).build();
    }

    @Bean
    NewTopic documentDeletedTopic() {
        return TopicBuilder.name(BloomTopics.DOCUMENT_DELETED).partitions(3).replicas(1).build();
    }

    /**
     * Retry with backoff, then park the record on {@code <topic>-dlt}. Boot
     * wires any CommonErrorHandler bean into the listener container factory.
     */
    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> template) {
        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(3);
        backOff.setInitialInterval(1_000);
        backOff.setMultiplier(2.0);
        backOff.setMaxInterval(10_000);
        return new DefaultErrorHandler(new DeadLetterPublishingRecoverer(template), backOff);
    }
}
