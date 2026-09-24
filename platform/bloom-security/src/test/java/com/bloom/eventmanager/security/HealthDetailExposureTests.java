package com.bloom.eventmanager.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pins the two halves of the {@code show-details: when-authorized} setting in
 * {@code config-repo/application.yml}.
 *
 * <p>{@code /actuator/health/**} has to stay permit-all — the Compose healthchecks run
 * {@code curl -fsS http://localhost:<port>/actuator/health/readiness} with no
 * credentials, and {@code depends_on: service_healthy} gates the whole stack on them.
 * With {@code show-details: always} that same anonymous request also returned the
 * discoveryComposite indicator (every registered service with its container IP and
 * port) and the host's disk paths. {@code when-authorized} keeps the probe's 200 and
 * its status field while withholding the detail, which is exactly the split these two
 * tests assert: break either half and one of them fails.
 *
 * <p>The properties below mirror {@code config-repo/application.yml}; the test suite is
 * hermetic and never reads the Config Server.
 */
@SpringBootTest(classes = SecurityProbeApplication.class, properties = {
        "bloom.security.internal-token=internal-secret",
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/never-called",
        "management.endpoint.health.show-details=when-authorized",
        "management.endpoint.health.probes.enabled=true"
})
@AutoConfigureMockMvc
class HealthDetailExposureTests {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void theComposeProbeStillGetsA200AndAStatusWithoutCredentials() throws Exception {
        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));

        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                // the topology leak: indicator names and their detail must not be here
                .andExpect(jsonPath("$.components").doesNotExist())
                .andExpect(jsonPath("$.details").doesNotExist());
    }

    @Test
    void anAuthenticatedCallerStillSeesTheFullDetail() throws Exception {
        mockMvc.perform(get("/actuator/health").with(jwt().jwt(j -> j.subject("bloom"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").exists());
    }
}
