package com.bloom.eventmanager.storage.config;

import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Configuration;

/**
 * Enables the Caffeine cache behind {@code DriveFolderConventions.listCompanyFolders}.
 *
 * <p>Deliberately not on {@code StorageServiceApplication}, for the reason
 * {@code retrieval-service}'s CachingConfig records: {@code @EnableCaching} there
 * installs the caching advisor into every {@code @WebMvcTest} slice too, and a
 * slice applies no {@code CacheAutoConfiguration}, so every controller test fails
 * on a missing {@code CacheManager}. As a scanned {@code @Configuration} it is
 * filtered out of web slices and still picked up by the full context.
 */
@Configuration(proxyBeanMethods = false)
@EnableCaching
public class CachingConfig {
}
