package com.bloom.eventmanager.retrieval;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.cloud.openfeign.EnableFeignClients;

// Caching is enabled by config/CachingConfig, not here — see the note there.
@SpringBootApplication(scanBasePackages = "com.bloom.eventmanager")
@ConfigurationPropertiesScan
@EnableFeignClients(basePackages = "com.bloom.eventmanager.retrieval.client")
public class RetrievalServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(RetrievalServiceApplication.class, args);
    }
}
