package com.bloom.eventmanager.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = SecurityProbeApplication.class, properties = {
        "bloom.security.internal-token=internal-secret",
        "bloom.security.permit-all=/probe/open",
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/never-called"
})
@Import(ProbeController.class)
@AutoConfigureMockMvc
class BloomSecurityIntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void anonymousCallsAre401() throws Exception {
        mockMvc.perform(get("/probe/secure")).andExpect(status().isUnauthorized());
    }

    @Test
    void theInternalTokenAuthenticatesAsService() throws Exception {
        mockMvc.perform(get("/probe/secure").header("X-Internal-Token", "internal-secret"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.principal").value("internal-service"));
    }

    @Test
    void aWrongInternalTokenIsStill401() throws Exception {
        mockMvc.perform(get("/probe/secure").header("X-Internal-Token", "nope")).andExpect(status().isUnauthorized());
    }

    @Test
    void aValidJwtAuthenticatesAsItsSubject() throws Exception {
        mockMvc.perform(get("/probe/secure").with(jwt().jwt(j -> j.subject("bloom"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.principal").value("bloom"));
    }

    @Test
    void permitAllPathsAndHealthAreOpen() throws Exception {
        mockMvc.perform(get("/probe/open")).andExpect(status().isOk());
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }
}
