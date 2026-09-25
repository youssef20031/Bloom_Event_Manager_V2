package com.bloom.eventmanager.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.micrometer.tracing.autoconfigure.TracingProperties;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.zipkin.autoconfigure.ZipkinProperties;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.profiles.active=native",
                "spring.cloud.config.server.native.search-locations=classpath:/test-config-repo",
                // distinct from the built-in default, so the assertion proves it is read
                "ZIPKIN_ENDPOINT=http://127.0.0.1:9411/api/v2/spans",
                // a test-only key, never a real secret; proves application.yml maps it to encrypt.key
                "ENCRYPT_KEY=test-only-encrypt-key-not-a-secret"
        })
@AutoConfigureTestRestTemplate
class ConfigServerApplicationTests {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ZipkinProperties zipkin;

    @Autowired
    private TracingProperties tracing;

    @Test
    void servesPropertiesForARequestedApplication() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "http://localhost:" + port + "/knowledge-service/default", String.class);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).contains("bloom.config-server.reachable");
        assertThat(response.getBody()).contains("true");
    }

    /**
     * This server does not import config-repo, so it never saw the tracing settings every
     * other service gets from there: it sampled at Boot's default 10% and posted to the
     * built-in localhost:9411, which inside a container is its own empty loopback.
     * Asserted on the properties beans Boot's exporter reads, so a key Boot 4 would drop
     * (the old management.zipkin.tracing.endpoint) cannot pass.
     */
    @Test
    void tracesEverythingToTheZipkinEndpointFromTheEnvironment() {
        assertThat(zipkin.getEndpoint()).isEqualTo("http://127.0.0.1:9411/api/v2/spans");
        assertThat(tracing.getSampling().getProbability()).isEqualTo(1.0f);
    }

    /**
     * {cipher} values are decrypted before they are served, for the native backend
     * as for any other: clients never see ciphertext and never need the key.
     */
    @Test
    void servesCipherValuesDecrypted() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "http://localhost:" + port + "/knowledge-service/default", String.class);

        assertThat(response.getBody()).contains("decrypted-ok");
        assertThat(response.getBody()).doesNotContain("{cipher}");
    }

    @Test
    void returnsAnEmptyPropertySourceListForAnUnknownApplication() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "http://localhost:" + port + "/no-such-service/default", String.class);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).contains("\"name\":\"no-such-service\"");
    }
}
