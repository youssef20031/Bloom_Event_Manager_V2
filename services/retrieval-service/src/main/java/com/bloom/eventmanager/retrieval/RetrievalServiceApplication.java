package com.bloom.eventmanager.retrieval;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cloud.openfeign.EnableFeignClients;

@SpringBootApplication(scanBasePackages = "com.bloom.eventmanager")
@ConfigurationPropertiesScan
@EnableCaching
@EnableFeignClients(basePackages = "com.bloom.eventmanager.retrieval.client")
public class RetrievalServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(RetrievalServiceApplication.class, args);
    }
}
