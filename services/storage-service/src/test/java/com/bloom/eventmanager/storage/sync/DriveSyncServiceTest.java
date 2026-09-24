package com.bloom.eventmanager.storage.sync;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import feign.FeignException;
import feign.Request;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.bloom.eventmanager.common.error.BloomException;
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
import com.bloom.eventmanager.storage.drive.StorageFileInfo;
import com.bloom.eventmanager.storage.kafka.EventPublisher;
import com.bloom.eventmanager.storage.oauth.DriveAuthManager;
import com.bloom.eventmanager.storage.oauth.DriveNotAuthenticatedException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DriveSyncServiceTest {

    private final DriveClient drive = mock(DriveClient.class);
    private final KnowledgeClient knowledge = mock(KnowledgeClient.class);
    private final EventPublisher publisher = mock(EventPublisher.class);
    private final DriveAuthManager auth = mock(DriveAuthManager.class);
    private final StorageProperties properties = new StorageProperties("", "", "", "http://cb", "root", 300_000L, 60_000L,
            "http://localhost:5173", List.of(), "default", 600_000L);
    private final DriveSyncService service = new DriveSyncService(drive, knowledge, publisher, auth, properties);

    @Test
    void syncAllPublishesDiscoveredForNewAndChangedFilesOnly() {
        when(auth.isAuthenticated()).thenReturn(true);
        when(drive.listFilesRecursive("root", "application/pdf")).thenReturn(List.of(
                file("a", "Clients/a.pdf", "t1"), file("b", "b.pdf", "t2"), file("c", "c.pdf", "t3")));
        when(knowledge.allDriveFiles()).thenReturn(Map.of("b", "t2", "c", "old"));

        SyncSummary summary = service.syncAll();

        assertThat(summary.filesQueued()).isEqualTo(2);
        assertThat(summary.filesSeen()).isEqualTo(3);
        ArgumentCaptor<Object> events = ArgumentCaptor.forClass(Object.class);
        verify(publisher).publish(eq(BloomTopics.DOCUMENT_DISCOVERED), eq("a"), events.capture());
        verify(publisher).publish(eq(BloomTopics.DOCUMENT_DISCOVERED), eq("c"), events.capture());
        DocumentDiscovered first = (DocumentDiscovered) events.getAllValues().get(0);
        assertThat(first.name()).isEqualTo("Clients/a.pdf");
        assertThat(first.folderPath()).isEqualTo("Clients");
        assertThat(first.modifiedTime()).isEqualTo("t1");
        assertThat(service.status().status()).isEqualTo("idle");
    }

    @Test
    void syncAllRequiresDriveAuthentication() {
        when(auth.isAuthenticated()).thenReturn(false);

        assertThatThrownBy(service::syncAll).isInstanceOf(DriveNotAuthenticatedException.class);
    }

    @Test
    void autoSyncPublishesDeletionsWithTheSourceLookedUpInKnowledge() {
        when(auth.isAuthenticated()).thenReturn(true);
        when(drive.listFilesRecursive("root", "application/pdf")).thenReturn(List.of());
        when(knowledge.allDriveFiles()).thenReturn(Map.of("gone", "t"));
        when(knowledge.driveFile("gone")).thenReturn(new DriveFileMetadata("Clients/gone.pdf", "t"));

        service.autoSync();

        verify(publisher).publish(BloomTopics.DOCUMENT_DELETED, "gone", new DocumentDeleted("gone", "Clients/gone.pdf"));
    }

    @Test
    void autoSyncSkipsADeletionWhoseSourceIsUnknown() {
        when(auth.isAuthenticated()).thenReturn(true);
        when(drive.listFilesRecursive("root", "application/pdf")).thenReturn(List.of());
        when(knowledge.allDriveFiles()).thenReturn(Map.of("gone", "t"));
        when(knowledge.driveFile("gone")).thenThrow(new FeignException.NotFound("nope",
                Request.create(Request.HttpMethod.GET, "/x", Map.of(), null, null, null), null, Map.of()));

        service.autoSync();

        verify(publisher, never()).publish(eq(BloomTopics.DOCUMENT_DELETED), anyString(), any());
    }

    @Test
    void autoSyncDoesNothingWhenUnauthenticatedOrUnchanged() {
        when(auth.isAuthenticated()).thenReturn(false);
        service.autoSync();
        verify(drive, never()).listFilesRecursive(anyString(), anyString());

        when(auth.isAuthenticated()).thenReturn(true);
        when(drive.listFilesRecursive("root", "application/pdf")).thenReturn(List.of(file("a", "a.pdf", "t")));
        when(knowledge.allDriveFiles()).thenReturn(Map.of("a", "t"));
        service.autoSync();
        verify(publisher, never()).publish(anyString(), anyString(), any());
    }

    @Test
    void onIndexedRecordsThePendingFilesModifiedTimeInKnowledge() {
        when(auth.isAuthenticated()).thenReturn(true);
        when(drive.listFilesRecursive("root", "application/pdf")).thenReturn(List.of(file("a", "a.pdf", "t9")));
        when(knowledge.allDriveFiles()).thenReturn(Map.of());
        service.syncAll();

        service.onIndexed(new DocumentIndexed("a.pdf", "a", 12, Instant.now()));

        verify(knowledge).upsertDriveFile("a", new DriveFileUpsert("a.pdf", "t9"));
    }

    @Test
    void onIndexedFallsBackToDriveWhenNothingIsPendingAfterARestart() {
        when(drive.fileInfo("a")).thenReturn(new StorageFileInfo("a", "a.pdf", "t-from-drive", null));

        service.onIndexed(new DocumentIndexed("a.pdf", "a", 12, Instant.now()));

        verify(knowledge).upsertDriveFile("a", new DriveFileUpsert("a.pdf", "t-from-drive"));
    }

    @Test
    void onIndexedIgnoresLocalIngestsThatHaveNoDriveFileId() {
        service.onIndexed(new DocumentIndexed("local.pdf", null, 3, Instant.now()));

        verify(knowledge, never()).upsertDriveFile(anyString(), any());
    }

    @Test
    void driveFailuresAreRoutedThroughAuthErrorHandling() {
        when(auth.isAuthenticated()).thenReturn(true);
        RuntimeException failure = new RuntimeException("invalid_grant");
        when(drive.listFilesRecursive(anyString(), anyString())).thenThrow(failure);

        assertThatThrownBy(service::syncAll).isSameAs(failure);
        verify(auth).handleAuthError(failure);
        assertThat(service.status().status()).isEqualTo("error");
    }

    @Test
    void aSecondSyncWhileOneRunsIs409() {
        when(auth.isAuthenticated()).thenReturn(true);
        service.stateForTesting().start();

        assertThatThrownBy(service::syncAll).isInstanceOf(BloomException.class).hasMessageContaining("already in progress");
    }

    private static StorageFile file(String id, String name, String modifiedTime) {
        return new StorageFile(id, name, "application/pdf", modifiedTime, 1L);
    }
}
