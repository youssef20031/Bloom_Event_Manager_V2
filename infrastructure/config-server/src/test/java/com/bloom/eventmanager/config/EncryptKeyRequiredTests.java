package com.bloom.eventmanager.config;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Without ENCRYPT_KEY the server must not start at all. Property binding leaves an
 * unresolvable {@code ${ENCRYPT_KEY}} as literal text, so without a check this server
 * used to start normally with the literal string {@code ${ENCRYPT_KEY}} as its key:
 * every {@code {cipher}} value was served as {@code invalid.<name>}, and
 * {@code POST /encrypt} handed out ciphertext anyone could decrypt.
 */
class EncryptKeyRequiredTests {

    @Test
    void refusesToStartWithoutEncryptKey() {
        assertThatThrownBy(() -> start())
                .hasStackTraceContaining("ENCRYPT_KEY is not set");
    }

    @Test
    void refusesToStartWithABlankEncryptKey() {
        assertThatThrownBy(() -> start("--ENCRYPT_KEY=   "))
                .hasStackTraceContaining("ENCRYPT_KEY is not set");
    }

    private static void start(String... extraArgs) {
        String[] args = new String[extraArgs.length + 3];
        args[0] = "--spring.profiles.active=native";
        args[1] = "--spring.cloud.config.server.native.search-locations=classpath:/test-config-repo";
        args[2] = "--server.port=0";
        System.arraycopy(extraArgs, 0, args, 3, extraArgs.length);
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(ConfigServerApplication.class)
                .environment(environmentWithoutEncryptKey())
                .run(args)) {
            // Reaching here means the server started: the assertion in the caller fails.
        }
    }

    /**
     * Hermetic whatever shell runs the build: a developer who exported ENCRYPT_KEY
     * would otherwise make the missing-key case start normally.
     */
    private static StandardEnvironment environmentWithoutEncryptKey() {
        StandardEnvironment environment = new StandardEnvironment();
        Map<String, Object> variables = new HashMap<>(environment.getSystemEnvironment());
        variables.keySet().removeIf(name -> name.equalsIgnoreCase("ENCRYPT_KEY"));
        environment.getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, variables));
        return environment;
    }
}
