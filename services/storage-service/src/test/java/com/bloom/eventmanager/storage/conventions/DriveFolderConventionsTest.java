package com.bloom.eventmanager.storage.conventions;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.bloom.eventmanager.storage.drive.DriveClient;
import com.bloom.eventmanager.storage.drive.StorageFile;
import com.bloom.eventmanager.storage.drive.UploadResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DriveFolderConventionsTest {

    private final DriveClient drive = mock(DriveClient.class);
    private final DriveFolderConventions conventions = new DriveFolderConventions(drive);

    @Test
    void sanitizeStripsPathCharactersButKeepsSpacesLikeV1Did() {
        assertThat(DriveFolderConventions.sanitizeFolderName("Acme Corp")).isEqualTo("Acme Corp");
        assertThat(DriveFolderConventions.sanitizeFolderName(" A/B:C*D?E\"F<G>H|I\\J ")).isEqualTo("ABCDEFGHIJ");
        assertThat(DriveFolderConventions.sanitizeFolderName("x".repeat(150))).hasSize(100);
    }

    @Test
    void uploadsIntoTheCompanyFolderCreatingItIfNeeded() {
        when(drive.findOrCreateFolder("root", "Acme Corp")).thenReturn("folder-1");
        when(drive.upload("folder-1", "brief.pdf", new byte[] { 1 }, "application/pdf"))
                .thenReturn(new UploadResult("file-1", "https://drive/view"));

        NamedFolderUpload result = conventions.uploadFileToNamedFolder("root", "Acme Corp", "brief.pdf", new byte[] { 1 }, "application/pdf");

        assertThat(result).isEqualTo(new NamedFolderUpload("folder-1", "file-1", "https://drive/view"));
    }

    @Test
    void chatBackupsGoUnderChatsSessionFolderWithV1Names() {
        when(drive.findOrCreateFolder("root", "Chats")).thenReturn("chats");
        when(drive.findOrCreateFolder("chats", "Session_2026-09-15T10-30")).thenReturn("session");
        when(drive.upload(eq("session"), eq("Chat_Backup_session_.pdf"), any(), eq("application/pdf")))
                .thenReturn(new UploadResult("f", null));

        NamedFolderUpload result = conventions.uploadSessionBackup("root", "session_1757932245_abc", new byte[] { 1 },
                Instant.parse("2026-09-15T10:30:45.123Z"));

        assertThat(result.folderId()).isEqualTo("session");
        verify(drive).upload("session", "Chat_Backup_session_.pdf", new byte[] { 1 }, "application/pdf");
    }

    @Test
    void listsCompanyFoldersWithBriefCountsExcludingChats() {
        when(drive.listFolders("root")).thenReturn(List.of(
                new StorageFile("f1", "Acme", "folder", "t", null),
                new StorageFile("f2", "Chats", "folder", "t", null),
                new StorageFile("f3", "Globex", "folder", "t", null)));
        when(drive.listFiles("f1", "application/pdf")).thenReturn(List.of(
                new StorageFile("p1", "a.pdf", "application/pdf", "t", 1L),
                new StorageFile("p2", "b.pdf", "application/pdf", "t", 1L)));
        when(drive.listFiles("f3", "application/pdf")).thenReturn(List.of());

        List<CompanyFolder> companies = conventions.listCompanyFolders("root");

        assertThat(companies).containsExactly(new CompanyFolder("f1", "Acme", 2), new CompanyFolder("f3", "Globex", 0));
    }
}
