package com.bloom.eventmanager.ingestion.config;

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

    /** ingestion-service owns this topic: it is the only producer. */
    @Bean
    NewTopic chunkBatchReadyTopic() {
        return TopicBuilder.name(BloomTopics.CHUNK_BATCH_READY).partitions(3).replicas(1).build();
    }

    /**
     * Consumed here, produced by Phase 3b's Drive sync. Declared for the same
     * reason knowledge-service declares the topics it consumes: whichever side
     * touches the topic first fixes its partition count, and a subscription
     * that arrives first would auto-create it with a single partition.
     */
    @Bean
    NewTopic documentDiscoveredTopic() {
        return TopicBuilder.name(BloomTopics.DOCUMENT_DISCOVERED).partitions(3).replicas(1).build();
    }

    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> template) {
        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(3);
        backOff.setInitialInterval(2_000);
        backOff.setMultiplier(2.0);
        backOff.setMaxInterval(30_000);
        return new DefaultErrorHandler(new DeadLetterPublishingRecoverer(template), backOff);
    }
}
