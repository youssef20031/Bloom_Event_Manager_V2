package com.bloom.eventmanager.common.error;

import java.net.URI;
import java.util.stream.Collectors;

import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final String TYPE_PREFIX = "https://bloom.eventmanager/problems/";

    @ExceptionHandler(BloomException.class)
    public ProblemDetail handleBloom(BloomException ex) {
        ProblemDetail problem = toProblem(ex.errorCode(), ex.getMessage());
        ex.properties().forEach(problem::setProperty);
        return problem;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return toProblem(ErrorCode.VALIDATION_FAILED, detail.isBlank() ? "Request validation failed" : detail);
    }

    /** Parser messages echo fragments of the body; keep them out of the response. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail handleUnreadable(HttpMessageNotReadableException ex) {
        return toProblem(ErrorCode.VALIDATION_FAILED, "Malformed request body");
    }

    /**
     * Anything not modelled as a BloomException is reported with a fixed detail
     * string. Exception messages from drivers routinely embed connection URIs
     * with credentials, so they must never reach a client.
     */
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex) {
        return toProblem(ErrorCode.INTERNAL_ERROR, "Unexpected error");
    }

    private ProblemDetail toProblem(ErrorCode code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(code.status(), detail);
        problem.setTitle(code.title());
        problem.setType(URI.create(TYPE_PREFIX + code.slug()));
        problem.setProperty("errorCode", code.name());
        return problem;
    }
}
