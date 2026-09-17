package com.bloom.eventmanager.chat.config;

import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Configuration;

/**
 * Enables the Caffeine cache behind {@code ChatSessionStore.countMentioning}.
 *
 * <p>Deliberately not on {@code ChatServiceApplication}, for the reason
 * {@code retrieval-service}'s and {@code storage-service}'s CachingConfig
 * record: {@code @EnableCaching} there installs the caching advisor into
 * every {@code @WebMvcTest} slice too, and a slice applies no
 * {@code CacheAutoConfiguration}, so every controller test fails on a
 * missing {@code CacheManager}. As a scanned {@code @Configuration} it is
 * filtered out of web slices and still picked up by the full context.
 */
@Configuration(proxyBeanMethods = false)
@EnableCaching
public class CachingConfig {
}
