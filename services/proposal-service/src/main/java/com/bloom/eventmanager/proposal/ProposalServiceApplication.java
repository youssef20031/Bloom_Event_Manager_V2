package com.bloom.eventmanager.proposal;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

@SpringBootApplication(scanBasePackages = "com.bloom.eventmanager")
@EnableFeignClients(basePackages = "com.bloom.eventmanager.proposal.client")
public class ProposalServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProposalServiceApplication.class, args);
    }
}
