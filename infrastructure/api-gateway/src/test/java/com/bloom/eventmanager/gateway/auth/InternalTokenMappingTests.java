package com.bloom.eventmanager.gateway.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;

import com.bloom.eventmanager.gateway.ApiGatewayApplication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The gateway's bloom.auth.internal-token is not set anywhere of its own: application.yml
 * points it at bloom.security.internal-token, the one shared value config-repo serves.
 * Every other gateway test sets bloom.auth.internal-token directly, so only this class
 * exercises that mapping.
 */
@SpringBootTest(properties = {
        "spring.config.import=",
        "eureka.client.enabled=false",
        "spring.cloud.discovery.enabled=false",
        "bloom.auth.user=bloom",
        "bloom.auth.password=correct horse battery staple",
        // Only the shared property, as the Config Server serves it.
        "bloom.security.internal-token=shared-internal-token"
})
class InternalTokenMappingTests {

    @Autowired
    private AuthProperties auth;

    @Test
    void bindsTheGatewaysInternalTokenFromTheSharedProperty() {
        assertThat(auth.internalToken()).isEqualTo("shared-internal-token");
    }

    /**
     * Without the Config Server's value the gateway must not start. An unresolvable
     * placeholder with no default binds as its own literal text, which @NotBlank
     * accepts, so the gateway used to start healthy and stamp the string
     * "${bloom.security.internal-token}" on every forwarded request.
     */
    @Test
    void refusesToStartWithoutTheSharedInternalToken() {
        assertThatThrownBy(() -> {
            try (ConfigurableApplicationContext context = new SpringApplicationBuilder(ApiGatewayApplication.class)
                    .run("--spring.config.import=",
                            "--eureka.client.enabled=false",
                            "--spring.cloud.discovery.enabled=false",
                            "--bloom.auth.user=bloom",
                            "--bloom.auth.password=correct horse battery staple",
                            "--server.port=0")) {
                // Reaching here means the gateway started: the assertion fails.
            }
        }).hasStackTraceContaining("internalToken");
    }
}
