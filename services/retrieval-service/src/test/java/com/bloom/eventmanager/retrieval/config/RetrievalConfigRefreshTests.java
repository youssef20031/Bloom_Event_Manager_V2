package com.bloom.eventmanager.retrieval.config;

import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.ConfigurationPropertiesBindException;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.cloud.context.refresh.ContextRefresher;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.bloom.eventmanager.retrieval.client.AiClient;
import com.bloom.eventmanager.retrieval.client.KnowledgeClient;
import com.bloom.eventmanager.retrieval.client.dto.ChunkMatch;
import com.bloom.eventmanager.retrieval.domain.RetrievalService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code bloom.retrieval.topK} is meant to be tunable on a running stack: edit
 * {@code config-repo/retrieval-service.yml}, {@code POST /actuator/refresh}, and the next
 * request that omits topK uses the new default. That takes two things, both pinned here.
 * {@link RetrievalProperties} must be a mutable bean that Spring Cloud's
 * {@code ConfigurationPropertiesRebinder} can rebind in place on the
 * {@code EnvironmentChangeEvent} a refresh publishes -- a record is bound once, through
 * its constructor, and the rebinder silently leaves it alone. And the refresh endpoint
 * must be exposed and reachable through bloom-security's chain with the internal token.
 *
 * <p>The exposure property below mirrors {@code config-repo/application.yml}; the suite
 * is hermetic and never reads the Config Server, so the new value is put straight into
 * the Environment, which is where the Config Server's property source lands on a refresh.
 */
@SpringBootTest(properties = {
        "spring.config.import=",
        "eureka.client.enabled=false",
        "spring.cloud.discovery.enabled=false",
        "bloom.security.internal-token=test-token",
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
        "management.endpoints.web.exposure.include=health,info,metrics,refresh",
        "bloom.retrieval.topK=4"
})
@AutoConfigureMockMvc
class RetrievalConfigRefreshTests {

    private static final float[] EMBEDDING = { 1f, 0f };

    /** The source name {@link TestPropertyValues#applyTo(ConfigurableEnvironment)} writes to. */
    private static final String CHANGED_SOURCE = "test";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ConfigurableEnvironment environment;

    @Autowired
    private ContextRefresher refresher;

    @Autowired
    private RetrievalService service;

    @MockitoBean
    private AiClient ai;

    @MockitoBean
    private KnowledgeClient knowledge;

    @BeforeEach
    void moreCandidatesThanAnyTopK() {
        when(knowledge.search(any())).thenReturn(chunks(50));
    }

    @AfterEach
    void restoreTheOriginalValue() {
        environment.getPropertySources().remove(CHANGED_SOURCE);
        refresher.refresh();
    }

    @Test
    void aRefreshRebindsTopKAndTheNextRequestWithoutOneUsesIt() {
        assertThat(defaultTopK()).isEqualTo(4);

        TestPropertyValues.of("bloom.retrieval.topK=7").applyTo(environment);
        refresher.refresh();

        assertThat(defaultTopK()).isEqualTo(7);
    }

    @Test
    void postingToTheRefreshEndpointWithTheInternalTokenAppliesTheChange() throws Exception {
        assertThat(defaultTopK()).isEqualTo(4);

        TestPropertyValues.of("bloom.retrieval.topK=9").applyTo(environment);
        mockMvc.perform(post("/actuator/refresh").header("X-Internal-Token", "test-token"))
                .andExpect(status().isOk());

        assertThat(defaultTopK()).isEqualTo(9);
    }

    /**
     * The binder calls the setter before validation runs, so without a guard a refresh
     * to an out-of-range value fails -- and leaves the service running on that value
     * anyway, @Max(100) notwithstanding. Found live: topK: 500 in config-repo, a 500 from
     * /actuator/refresh, then 500-chunk answers.
     */
    @Test
    void anOutOfRangeValueFailsTheRefreshAndTheLastValidTopKStaysInEffect() {
        TestPropertyValues.of("bloom.retrieval.topK=7").applyTo(environment);
        refresher.refresh();

        TestPropertyValues.of("bloom.retrieval.topK=500").applyTo(environment);
        assertThatThrownBy(() -> refresher.refresh())
                .isInstanceOf(ConfigurationPropertiesBindException.class)
                .hasMessageContaining("bloom.retrieval");

        assertThat(defaultTopK()).isEqualTo(7);
    }

    @Test
    void anAnonymousRefreshIs401AndChangesNothing() throws Exception {
        TestPropertyValues.of("bloom.retrieval.topK=11").applyTo(environment);

        mockMvc.perform(post("/actuator/refresh")).andExpect(status().isUnauthorized());

        assertThat(defaultTopK()).isEqualTo(4);
    }

    /**
     * Observed through RetrievalService rather than the properties bean, because the
     * service is what has to see the change: the plain path searches 2×topK and keeps topK.
     */
    private int defaultTopK() {
        return service.retrieve(null, EMBEDDING, null, false).chunks().size();
    }

    private static List<ChunkMatch> chunks(int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> new ChunkMatch("c" + i, "text " + i, "source.pdf", 1, 0.8))
                .toList();
    }
}
