package com.bloom.eventmanager.storage.client;

import java.util.Map;

import org.springframework.stereotype.Component;

import com.bloom.eventmanager.common.error.ExternalServiceException;
import com.bloom.eventmanager.storage.client.dto.DriveFileMetadata;
import com.bloom.eventmanager.storage.client.dto.DriveFileUpsert;

/**
 * Runs when knowledge-service is down or its breaker is open. Storage's sync loop
 * compares Drive against the indexed state, so an empty answer here would read as
 * "nothing is indexed" and trigger a full re-ingest of every file. Throwing leaves
 * the existing index untouched and lets the next sync try again.
 */
@Component
public class KnowledgeClientFallback implements KnowledgeClient {

    @Override
    public Map<String, String> allDriveFiles() {
        throw new ExternalServiceException("knowledge-service unavailable (circuit open)", null);
    }

    @Override
    public DriveFileMetadata driveFile(String driveFileId) {
        throw new ExternalServiceException("knowledge-service unavailable (circuit open)", null);
    }

    @Override
    public void upsertDriveFile(String driveFileId, DriveFileUpsert body) {
        throw new ExternalServiceException("knowledge-service unavailable (circuit open)", null);
    }
}
