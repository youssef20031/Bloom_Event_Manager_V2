package com.bloom.eventmanager.storage.client;

import java.util.Map;

import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import com.bloom.eventmanager.common.error.ExternalServiceException;
import com.bloom.eventmanager.common.feign.FeignFallbacks;
import com.bloom.eventmanager.storage.client.dto.DriveFileMetadata;
import com.bloom.eventmanager.storage.client.dto.DriveFileUpsert;

/**
 * Runs when knowledge-service is down or its breaker is open. Storage's sync loop
 * compares Drive against the indexed state, so an empty answer here would read as
 * "nothing is indexed" and trigger a full re-ingest of every file. Throwing leaves
 * the existing index untouched and lets the next sync try again.
 *
 * <p>A {@code FallbackFactory} rather than a plain fallback because the invocation
 * handler routes <em>every</em> throwable here, including the {@code FeignException}
 * raised for a well-formed 4xx. Specifically, {@code driveFile} answers 404 for a Drive
 * file knowledge-service holds no record of; {@link FeignFallbacks#failure} hands that
 * back unchanged so {@code DriveSyncService.publishDeleted}'s
 * {@code catch (FeignException.NotFound)} still logs the benign warning instead of
 * recording a sync error.
 */
@Component
public class KnowledgeClientFallbackFactory implements FallbackFactory<KnowledgeClient> {

    @Override
    public KnowledgeClient create(Throwable cause) {
        return new KnowledgeClient() {

            @Override
            public Map<String, String> allDriveFiles() {
                throw failure(cause);
            }

            @Override
            public DriveFileMetadata driveFile(String driveFileId) {
                throw failure(cause);
            }

            @Override
            public void upsertDriveFile(String driveFileId, DriveFileUpsert body) {
                throw failure(cause);
            }
        };
    }

    private static RuntimeException failure(Throwable cause) {
        return FeignFallbacks.failure(cause,
                () -> new ExternalServiceException("knowledge-service unavailable (circuit open)", cause));
    }
}
