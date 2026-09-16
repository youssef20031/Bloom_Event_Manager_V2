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

    @Test
    void mapsBeanValidationFailuresTo400WithFieldDetail() throws Exception {
        java.lang.reflect.Method method = Probe.class.getDeclaredMethod("handle", String.class);
        org.springframework.core.MethodParameter parameter = new org.springframework.core.MethodParameter(method, 0);
        org.springframework.validation.BeanPropertyBindingResult binding =
                new org.springframework.validation.BeanPropertyBindingResult(new Object(), "request");
        binding.addError(new org.springframework.validation.FieldError("request", "text", "must not be blank"));

        ProblemDetail problem = handler.handleValidation(
                new org.springframework.web.bind.MethodArgumentNotValidException(parameter, binding));

        assertThat(problem.getStatus()).isEqualTo(400);
        assertThat(problem.getDetail()).isEqualTo("text must not be blank");
        assertThat(problem.getProperties()).containsEntry("errorCode", "VALIDATION_FAILED");
    }

    @Test
    void mapsUnreadableRequestBodiesTo400WithoutEchoingParserInternals() {
        ProblemDetail problem = handler.handleUnreadable(
                new org.springframework.http.converter.HttpMessageNotReadableException(
                        "JSON parse error: Unexpected character at line 3", (org.springframework.http.HttpInputMessage) null));

        assertThat(problem.getStatus()).isEqualTo(400);
        assertThat(problem.getDetail()).isEqualTo("Malformed request body");
        assertThat(problem.getProperties()).containsEntry("errorCode", "VALIDATION_FAILED");
    }

    @Test
    void copiesExceptionPropertiesOntoTheProblemDetail() {
        BloomException withProps = new BloomException(ErrorCode.DRIVE_NOT_AUTHENTICATED, "Google Drive not authenticated.") {
            @Override
            public java.util.Map<String, Object> properties() {
                return java.util.Map.of("authUrl", "/api/v1/rag/sync/auth?force=true");
            }
        };

        ProblemDetail problem = handler.handleBloom(withProps);

        assertThat(problem.getStatus()).isEqualTo(401);
        assertThat(problem.getProperties()).containsEntry("authUrl", "/api/v1/rag/sync/auth?force=true")
                .containsEntry("errorCode", "DRIVE_NOT_AUTHENTICATED");
    }

    @Test
    void conflictMapsTo409() {
        ProblemDetail problem = handler.handleBloom(new BloomException(ErrorCode.CONFLICT, "Sync already in progress"));

        assertThat(problem.getStatus()).isEqualTo(409);
    }

    @SuppressWarnings("unused")
    private static final class Probe {
        void handle(String text) {
        }
    }
}