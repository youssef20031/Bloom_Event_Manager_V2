package com.bloom.eventmanager.storage.config;

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

    /** storage-service owns these two topics: it is the only producer. */
    @Bean
    NewTopic documentDiscoveredTopic() {
        return TopicBuilder.name(BloomTopics.DOCUMENT_DISCOVERED).partitions(3).replicas(1).build();
    }

    @Bean
    NewTopic documentDeletedTopic() {
        return TopicBuilder.name(BloomTopics.DOCUMENT_DELETED).partitions(3).replicas(1).build();
    }

    /**
     * Consumed here, produced by knowledge-service. Declared for the same reason
     * the other services declare what they consume: whichever side touches the
     * topic first fixes its partition count, and a subscription that arrives
     * first would auto-create it with a single partition — silently leaving this
     * consumer with a one-partition view of a three-partition topic.
     */
    @Bean
    NewTopic documentIndexedTopic() {
        return TopicBuilder.name(BloomTopics.DOCUMENT_INDEXED).partitions(3).replicas(1).build();
    }

    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> template) {
        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(3);
        backOff.setInitialInterval(1_000);
        backOff.setMultiplier(2.0);
        backOff.setMaxInterval(10_000);
        return new DefaultErrorHandler(new DeadLetterPublishingRecoverer(template), backOff);
    }
}
