package com.bloom.eventmanager.discovery;

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

// ZIPKIN_ENDPOINT is distinct from the built-in default, so the tracing assertion proves it is read.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "ZIPKIN_ENDPOINT=http://127.0.0.1:9411/api/v2/spans")
@AutoConfigureTestRestTemplate
class DiscoveryServerApplicationTests {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ZipkinProperties zipkin;

    @Autowired
    private TracingProperties tracing;

    @Test
    void exposesEurekaRegistryEndpoint() {
        ResponseEntity<String> response =
                restTemplate.getForEntity("http://localhost:" + port + "/eureka/apps", String.class);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
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

    @Test
    void reportsHealthUp() {
        ResponseEntity<String> response =
                restTemplate.getForEntity("http://localhost:" + port + "/actuator/health", String.class);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
    }
}
