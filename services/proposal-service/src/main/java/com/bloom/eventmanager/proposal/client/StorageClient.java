package com.bloom.eventmanager.proposal.client;

import java.util.List;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import com.bloom.eventmanager.proposal.client.dto.CompanyFileRequest;
import com.bloom.eventmanager.proposal.client.dto.CompanyFileResponse;
import com.bloom.eventmanager.proposal.client.dto.CompanyFolder;

@FeignClient(name = "storage-service", fallbackFactory = StorageClientFallbackFactory.class)
public interface StorageClient {

    @GetMapping("/api/v1/storage/companies")
    List<CompanyFolder> companies();

    @PostMapping("/api/v1/storage/company-files")
    CompanyFileResponse uploadCompanyFile(@RequestBody CompanyFileRequest request);
}
