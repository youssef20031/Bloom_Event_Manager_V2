package com.bloom.eventmanager.storage.config;

import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.MongoTemplate;

import com.bloom.eventmanager.storage.oauth.OAuthStateStore;

/**
 * Builds {@link OAuthStateStore}: it cannot be a {@code @Component} itself
 * because its constructor takes a {@link Duration}, which Spring has no way to
 * autowire.
 */
@Configuration
public class OAuthStateConfig {

    @Bean
    OAuthStateStore oauthStateStore(MongoTemplate mongoTemplate, StorageProperties properties) {
        return new OAuthStateStore(mongoTemplate, Duration.ofMillis(properties.stateTtlMs()));
    }
}
