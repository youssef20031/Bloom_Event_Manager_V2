package com.bloom.eventmanager.storage.client;

import java.util.Map;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;

import com.bloom.eventmanager.storage.client.dto.DriveFileMetadata;
import com.bloom.eventmanager.storage.client.dto.DriveFileUpsert;

@FeignClient(name = "knowledge-service", fallback = KnowledgeClientFallback.class)
public interface KnowledgeClient {

    @GetMapping("/api/v1/drive-files")
    Map<String, String> allDriveFiles();

    /** Throws feign.FeignException.NotFound when the id is unknown. */
    @GetMapping("/api/v1/drive-files/{driveFileId}")
    DriveFileMetadata driveFile(@PathVariable("driveFileId") String driveFileId);

    @PutMapping("/api/v1/drive-files/{driveFileId}")
    void upsertDriveFile(@PathVariable("driveFileId") String driveFileId, @RequestBody DriveFileUpsert body);
}
