package com.bloom.eventmanager.retrieval.config;

import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Configuration;

/**
 * Enables the Caffeine cache behind {@code QueryVariationService}.
 *
 * <p>Deliberately not on {@code RetrievalServiceApplication}: {@code @EnableCaching}
 * there would install the caching advisor into every {@code @WebMvcTest} slice too,
 * and a slice applies no {@code CacheAutoConfiguration}, so every controller test
 * would fail on a missing {@code CacheManager}. As a scanned {@code @Configuration}
 * it is filtered out of web slices and still picked up by the full context.
 */
@Configuration(proxyBeanMethods = false)
@EnableCaching
public class CachingConfig {
}
