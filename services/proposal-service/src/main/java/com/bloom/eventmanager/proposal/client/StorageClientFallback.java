package com.bloom.eventmanager.proposal.client;

import java.util.List;

import org.springframework.stereotype.Component;

import com.bloom.eventmanager.common.error.ServiceUnavailableException;
import com.bloom.eventmanager.proposal.client.dto.CompanyFileRequest;
import com.bloom.eventmanager.proposal.client.dto.CompanyFileResponse;
import com.bloom.eventmanager.proposal.client.dto.CompanyFolder;

/**
 * Runs when storage-service is down or its breaker is open. proposal-service
 * is called directly by the browser, so throwing surfaces a clean 503
 * ProblemDetail instead of leaving the request to time out or return a
 * misleading 502.
 */
@Component
public class StorageClientFallback implements StorageClient {

    @Override
    public List<CompanyFolder> companies() {
        throw new ServiceUnavailableException("storage-service unavailable (circuit open)", null);
    }

    @Override
    public CompanyFileResponse uploadCompanyFile(CompanyFileRequest request) {
        throw new ServiceUnavailableException("storage-service unavailable (circuit open)", null);
    }
}
