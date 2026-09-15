package com.bloom.eventmanager.retrieval.domain;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.bloom.eventmanager.retrieval.client.AiClient;
import com.bloom.eventmanager.retrieval.client.KnowledgeClient;
import com.bloom.eventmanager.retrieval.client.dto.GenerateResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "spring.config.import=",
        "eureka.client.enabled=false",
        "spring.cloud.discovery.enabled=false"
})
class QueryVariationCachingTests {

    @Autowired
    private QueryVariationService service;

    @MockitoBean
    private AiClient ai;

    @MockitoBean
    private KnowledgeClient knowledge;

    @Test
    void secondCallForTheSameQueryIsServedFromCache() {
        when(ai.generate(any())).thenReturn(new GenerateResponse("{\"specific\": \"s\", \"broad\": \"b\"}", null));

        service.variations("cached query");
        service.variations("cached query");

        verify(ai, times(1)).generate(any());
    }

    @Test
    void failuresAreNotCached() {
        when(ai.generate(any()))
                .thenReturn(new GenerateResponse("garbage", null))
                .thenReturn(new GenerateResponse("{\"specific\": \"s\", \"broad\": \"b\"}", null));

        assertThatThrownBy(() -> service.variations("flaky query")).isInstanceOf(RuntimeException.class);
        assertThat(service.variations("flaky query").specific()).isEqualTo("s");

        verify(ai, times(2)).generate(any());
    }
}
