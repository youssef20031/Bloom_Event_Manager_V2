package com.bloom.eventmanager.proposal;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Whole-branch review finding: no {@code @SpringBootTest} existed anywhere
 * under proposal-service, unlike every other service and both infrastructure
 * modules. Every one of its eight test classes is a {@code @WebMvcTest} slice
 * or a plain unit test, neither of which instantiates the real Feign clients,
 * {@code @EnableFeignClients}, the config-client import, or the component
 * scan — including {@link com.bloom.eventmanager.proposal.domain.GenerateProposalUseCase}'s
 * {@code JsonMapper} injection. This is the first time this service's Spring
 * context has ever actually been built.
 */
@SpringBootTest(properties = {
        "spring.config.import=",
        "eureka.client.enabled=false",
        "spring.cloud.discovery.enabled=false",
        "bloom.security.internal-token=test-token",
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
})
class ProposalServiceApplicationTests {

    @Test
    void contextLoads() {
    }
}
