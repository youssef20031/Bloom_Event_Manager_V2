package com.bloom.eventmanager.common.error;

import org.junit.jupiter.api.Test;
import org.springframework.http.ProblemDetail;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void mapsNotFoundTo404WithTypeTitleAndErrorCode() {
        ProblemDetail problem = handler.handleBloom(new NotFoundException("chunk 42 not found"));

        assertThat(problem.getStatus()).isEqualTo(404);
        assertThat(problem.getTitle()).isEqualTo("Resource Not Found");
        assertThat(problem.getDetail()).isEqualTo("chunk 42 not found");
        assertThat(problem.getType()).hasToString("https://bloom.eventmanager/problems/not-found");
        assertThat(problem.getProperties()).containsEntry("errorCode", "NOT_FOUND");
    }

    @Test
    void mapsExternalServiceFailureTo502() {
        ProblemDetail problem = handler.handleBloom(
                new ExternalServiceException("gemini unavailable", new RuntimeException("connect timeout")));

        assertThat(problem.getStatus()).isEqualTo(502);
        assertThat(problem.getProperties()).containsEntry("errorCode", "EXTERNAL_SERVICE_ERROR");
    }

    @Test
    void doesNotLeakInternalDetailForUnexpectedExceptions() {
        ProblemDetail problem = handler.handleUnexpected(
                new IllegalStateException("mongodb://user:hunter2@cluster0"));

        assertThat(problem.getStatus()).isEqualTo(500);
        assertThat(problem.getDetail()).isEqualTo("Unexpected error");
        assertThat(problem.getDetail()).doesNotContain("hunter2");
    }
}