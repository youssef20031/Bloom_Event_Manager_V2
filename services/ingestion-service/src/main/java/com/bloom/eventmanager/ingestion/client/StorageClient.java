package com.bloom.eventmanager.ingestion.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/** Implemented by storage-service in Phase 3b. */
@FeignClient(name = "storage-service")
public interface StorageClient {

    @GetMapping(value = "/api/v1/storage/files/{fileId}/content", produces = "application/octet-stream")
    byte[] download(@PathVariable("fileId") String fileId);
}
