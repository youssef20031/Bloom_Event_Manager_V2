package com.bloom.eventmanager.storage.sync;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import feign.FeignException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.bloom.eventmanager.common.error.BloomException;
import com.bloom.eventmanager.common.error.ErrorCode;
import com.bloom.eventmanager.events.BloomTopics;
import com.bloom.eventmanager.events.DocumentDeleted;
import com.bloom.eventmanager.events.DocumentDiscovered;
import com.bloom.eventmanager.events.DocumentIndexed;
import com.bloom.eventmanager.storage.client.KnowledgeClient;
import com.bloom.eventmanager.storage.client.dto.DriveFileMetadata;
import com.bloom.eventmanager.storage.client.dto.DriveFileUpsert;
import com.bloom.eventmanager.storage.config.StorageProperties;
import com.bloom.eventmanager.storage.drive.DriveClient;
import com.bloom.eventmanager.storage.drive.StorageFile;
import com.bloom.eventmanager.storage.kafka.EventPublisher;
import com.bloom.eventmanager.storage.oauth.DriveAuthManager;
import com.bloom.eventmanager.storage.oauth.DriveNotAuthenticatedException;

/**
 * V1's syncController without the inline ingestion: changes become events.
 *
 * <p>Change detection compares Drive against the id → modifiedTime map
 * knowledge-service keeps on Document nodes. That map is only updated here,
 * on {@link #onIndexed}, once knowledge-service reports a document stored —
 * so a file that failed to ingest is picked up again on the next poll.
 */
@Service
public class DriveSyncService {

    private static final Logger log = LoggerFactory.getLogger(DriveSyncService.class);
    private static final String PDF = "application/pdf";

    public record SyncStatus(String status, boolean isAuthenticated, Instant lastSyncTime,
            SyncState.LastResult lastResult, String authUrl) {
    }

    private record PendingFile(String name, String modifiedTime) {
    }

    private final DriveClient drive;
    private final KnowledgeClient knowledge;
    private final EventPublisher publisher;
    private final DriveAuthManager auth;
    private final StorageProperties properties;
    private final SyncState state = new SyncState();
    private final Map<String, PendingFile> pending = new ConcurrentHashMap<>();

    public DriveSyncService(DriveClient drive, KnowledgeClient knowledge, EventPublisher publisher,
            DriveAuthManager auth, StorageProperties properties) {
        this.drive = drive;
        this.knowledge = knowledge;
        this.publisher = publisher;
        this.auth = auth;
        this.properties = properties;
    }

    /** Manual full sync: every PDF whose modifiedTime differs from what knowledge-service recorded. */
    public SyncSummary syncAll() {
        requireAuthenticated();
        if (!state.tryStart()) {
            throw new BloomException(ErrorCode.CONFLICT, "Sync already in progress");
        }
        try {
            List<StorageFile> files = drive.listFilesRecursive(properties.rootFolderId(), PDF);
            Map<String, String> known = knowledge.allDriveFiles();
            int queued = 0;
            for (StorageFile file : files) {
                if (!file.modifiedTime().equals(known.get(file.id()))) {
                    publishDiscovered(file);
                    queued++;
                }
            }
            state.finish(queued, files.size(), List.of());
            log.info("Sync: {} of {} Drive files queued for ingestion", queued, files.size());
            return new SyncSummary(queued, files.size(), state.lastSyncTime());
        }
        catch (RuntimeException ex) {
            auth.handleAuthError(ex);
            state.error();
            throw ex;
        }
    }

    public ChangeSet checkForUpdates() {
        requireAuthenticated();
        return ChangeDetector.detect(drive.listFilesRecursive(properties.rootFolderId(), PDF), knowledge.allDriveFiles());
    }

    /** The poll loop body. Never throws — it runs on a scheduler thread. */
    public void autoSync() {
        if (!auth.isAuthenticated()) {
            log.debug("AutoSync skipped: not authenticated");
            return;
        }
        if (state.isSyncing()) {
            log.debug("AutoSync skipped: sync already in progress");
            return;
        }
        try {
            ChangeSet changes = checkForUpdates();
            if (!changes.hasChanges()) {
                log.debug("AutoSync: no changes");
                return;
            }
            log.info("AutoSync: {} added, {} modified, {} deleted",
                    changes.added().size(), changes.modified().size(), changes.deleted().size());
            state.start();
            List<String> errors = new ArrayList<>();
            int queued = 0;
            for (StorageFile file : changes.added()) {
                publishDiscovered(file);
                queued++;
            }
            for (StorageFile file : changes.modified()) {
                publishDiscovered(file);
                queued++;
            }
            for (String deletedId : changes.deleted()) {
                publishDeleted(deletedId, errors);
            }
            state.finish(queued, changes.added().size() + changes.modified().size(), errors);
        }
        catch (RuntimeException ex) {
            auth.handleAuthError(ex);
            state.error();
            log.error("AutoSync failed", ex);
        }
    }

    /** knowledge-service stored the document: record the modifiedTime it was ingested at. */
    public void onIndexed(DocumentIndexed event) {
        if (event.driveFileId() == null) {
            return; // local-directory ingest; nothing to track
        }
        PendingFile pendingFile = pending.remove(event.driveFileId());
        String modifiedTime = pendingFile != null
                ? pendingFile.modifiedTime()
                : drive.fileInfo(event.driveFileId()).modifiedTime(); // restarted mid-flight: ask Drive
        knowledge.upsertDriveFile(event.driveFileId(), new DriveFileUpsert(event.source(), modifiedTime));
        log.info("Recorded Drive metadata for {} ({} chunks)", event.source(), event.chunkCount());
    }

    public SyncStatus status() {
        boolean authenticated = auth.isAuthenticated();
        String authUrl = null;
        if (!authenticated && auth.isConfigured()) {
            authUrl = auth.authUrl();
        }
        return new SyncStatus(state.status(), authenticated, state.lastSyncTime(), state.lastResult(), authUrl);
    }

    SyncState stateForTesting() {
        return state;
    }

    private void publishDiscovered(StorageFile file) {
        pending.put(file.id(), new PendingFile(file.name(), file.modifiedTime()));
        publisher.publish(BloomTopics.DOCUMENT_DISCOVERED, file.id(),
                new DocumentDiscovered(file.id(), file.name(), file.mimeType(), file.modifiedTime(), folderPath(file.name())));
    }

    private void publishDeleted(String driveFileId, List<String> errors) {
        try {
            DriveFileMetadata metadata = knowledge.driveFile(driveFileId);
            publisher.publish(BloomTopics.DOCUMENT_DELETED, driveFileId, new DocumentDeleted(driveFileId, metadata.source()));
        }
        catch (FeignException.NotFound notFound) {
            log.warn("Deleted Drive file {} has no recorded document; nothing to remove", driveFileId);
        }
        catch (RuntimeException ex) {
            log.error("Failed to handle deletion of Drive file {}", driveFileId, ex);
            errors.add("delete " + driveFileId + ": " + ex.getMessage());
        }
    }

    private void requireAuthenticated() {
        if (!auth.isAuthenticated()) {
            throw new DriveNotAuthenticatedException();
        }
    }

    private static String folderPath(String prefixedName) {
        int slash = prefixedName.lastIndexOf('/');
        return slash == -1 ? "" : prefixedName.substring(0, slash);
    }
}
