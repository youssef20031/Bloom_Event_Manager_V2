package com.bloom.eventmanager.storage.conventions;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

import com.bloom.eventmanager.storage.drive.DriveClient;
import com.bloom.eventmanager.storage.drive.StorageFile;
import com.bloom.eventmanager.storage.drive.UploadResult;

/**
 * Bloom's folder layout on Drive: one folder per company under the root,
 * chat backups under Chats/Session_&lt;timestamp&gt;/. V1's driveFolderConventions.ts.
 */
@Component
public class DriveFolderConventions {

    static final String CHATS_FOLDER = "Chats";
    private static final String PDF = "application/pdf";

    private final DriveClient drive;

    public DriveFolderConventions(DriveClient drive) {
        this.drive = drive;
    }

    /**
     * Removes characters Drive/Windows reject. Does NOT replace whitespace:
     * V1's intended {@code /\s+/} was written as {@code /\\s+/} and never
     * matched, so existing company folders keep their spaces.
     */
    public static String sanitizeFolderName(String name) {
        String cleaned = name.replaceAll("[<>:\"/\\\\|?*]", "").trim();
        return cleaned.length() > 100 ? cleaned.substring(0, 100) : cleaned;
    }

    @CacheEvict(cacheNames = "companyFolders", key = "#rootFolderId")
    public NamedFolderUpload uploadFileToNamedFolder(String rootFolderId, String folderName, String fileName,
            byte[] content, String mimeType) {
        String folderId = drive.findOrCreateFolder(rootFolderId, sanitizeFolderName(folderName));
        UploadResult result = drive.upload(folderId, fileName, content, mimeType);
        return new NamedFolderUpload(folderId, result.fileId(), result.webViewLink());
    }

    public NamedFolderUpload uploadSessionBackup(String rootFolderId, String sessionId, byte[] content, Instant now) {
        String chatsFolderId = drive.findOrCreateFolder(rootFolderId, CHATS_FOLDER);
        // V1: date.toISOString().replace(/[:.]/g, "-").slice(0, 16)  -> "2026-09-15T10-30"
        String sessionFolderName = "Session_" + DateTimeFormatter.ISO_INSTANT.format(now).replaceAll("[:.]", "-").substring(0, 16);
        String sessionFolderId = drive.findOrCreateFolder(chatsFolderId, sessionFolderName);
        String fileName = "Chat_Backup_" + sessionId.substring(0, Math.min(8, sessionId.length())) + ".pdf";
        UploadResult result = drive.upload(sessionFolderId, fileName, content, PDF);
        return new NamedFolderUpload(sessionFolderId, result.fileId(), result.webViewLink());
    }

    /** 1 + N Drive round-trips; cached five minutes and evicted by our own uploads. */
    @Cacheable(cacheNames = "companyFolders", key = "#rootFolderId")
    public List<CompanyFolder> listCompanyFolders(String rootFolderId) {
        List<CompanyFolder> companies = new ArrayList<>();
        for (StorageFile folder : drive.listFolders(rootFolderId)) {
            if (CHATS_FOLDER.equals(folder.name())) {
                continue;
            }
            int briefs = drive.listFiles(folder.id(), PDF).size();
            companies.add(new CompanyFolder(folder.id(), folder.name(), briefs));
        }
        return companies;
    }
}
