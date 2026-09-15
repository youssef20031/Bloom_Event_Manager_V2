package com.bloom.eventmanager.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.profiles.active=native",
                "spring.cloud.config.server.native.search-locations=classpath:/test-config-repo"
        })
@AutoConfigureTestRestTemplate
class ConfigServerApplicationTests {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void servesPropertiesForARequestedApplication() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "http://localhost:" + port + "/knowledge-service/default", String.class);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).contains("bloom.config-server.reachable");
        assertThat(response.getBody()).contains("true");
    }

    @Test
    void returnsAnEmptyPropertySourceListForAnUnknownApplication() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "http://localhost:" + port + "/no-such-service/default", String.class);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).contains("\"name\":\"no-such-service\"");
    }
}
