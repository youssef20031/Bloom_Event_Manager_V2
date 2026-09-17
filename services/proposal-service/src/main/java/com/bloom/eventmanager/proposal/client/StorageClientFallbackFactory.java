package com.bloom.eventmanager.proposal.client;

import java.util.List;

import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import com.bloom.eventmanager.common.error.ServiceUnavailableException;
import com.bloom.eventmanager.common.feign.FeignFallbacks;
import com.bloom.eventmanager.proposal.client.dto.CompanyFileRequest;
import com.bloom.eventmanager.proposal.client.dto.CompanyFileResponse;
import com.bloom.eventmanager.proposal.client.dto.CompanyFolder;

/**
 * Runs when storage-service is down or its breaker is open. proposal-service
 * is called directly by the browser, so throwing surfaces a clean 503
 * ProblemDetail instead of leaving the request to time out or return a
 * misleading 502.
 *
 * <p>A {@code FallbackFactory} rather than a plain fallback because the invocation
 * handler routes <em>every</em> throwable here, including the {@code FeignException}
 * raised for a well-formed 4xx. Specifically, storage-service answers 401 whenever
 * Drive consent has not been given yet; {@link FeignFallbacks#failure} hands that back
 * unchanged so {@code StorageErrors.translate}'s
 * {@code FeignException.Unauthorized -> DriveNotAuthenticatedException} branch still
 * fires and the SPA still gets its {@code authUrl}.
 */
@Component
public class StorageClientFallbackFactory implements FallbackFactory<StorageClient> {

    @Override
    public StorageClient create(Throwable cause) {
        return new StorageClient() {

            @Override
            public List<CompanyFolder> companies() {
                throw failure(cause);
            }

            @Override
            public CompanyFileResponse uploadCompanyFile(CompanyFileRequest request) {
                throw failure(cause);
            }
        };
    }

    private static RuntimeException failure(Throwable cause) {
        return FeignFallbacks.failure(cause,
                () -> new ServiceUnavailableException("storage-service unavailable (circuit open)", cause));
    }
}
