package com.bloom.eventmanager.storage.api;

import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bloom.eventmanager.common.error.BloomException;
import com.bloom.eventmanager.common.error.ErrorCode;
import com.bloom.eventmanager.storage.api.dto.ChatBackupRequest;
import com.bloom.eventmanager.storage.api.dto.CompanyFileRequest;
import com.bloom.eventmanager.storage.api.dto.FileUploadRequest;
import com.bloom.eventmanager.storage.api.dto.FolderRequest;
import com.bloom.eventmanager.storage.config.StorageProperties;
import com.bloom.eventmanager.storage.conventions.CompanyFolder;
import com.bloom.eventmanager.storage.conventions.DriveFolderConventions;
import com.bloom.eventmanager.storage.conventions.NamedFolderUpload;
import com.bloom.eventmanager.storage.drive.DriveClient;
import com.bloom.eventmanager.storage.drive.UploadResult;
import com.bloom.eventmanager.storage.oauth.DriveAuthManager;
import com.bloom.eventmanager.storage.oauth.DriveNotAuthenticatedException;

/** Generic file/folder primitives plus Bloom's folder conventions, for the other services. */
@RestController
@RequestMapping("/api/v1/storage")
public class FileController {

    private final DriveClient drive;
    private final DriveFolderConventions conventions;
    private final DriveAuthManager auth;
    private final StorageProperties properties;

    public FileController(DriveClient drive, DriveFolderConventions conventions, DriveAuthManager auth,
            StorageProperties properties) {
        this.drive = drive;
        this.conventions = conventions;
        this.auth = auth;
        this.properties = properties;
    }

    @GetMapping(value = "/files/{fileId}/content", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<byte[]> download(@PathVariable String fileId) {
        requireDriveAuth();
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM).body(drive.download(fileId));
    }

    @PostMapping("/files")
    public UploadResult upload(@Valid @RequestBody FileUploadRequest request) {
        requireDriveAuth();
        return drive.upload(request.folderId(), request.fileName(), decode(request.contentBase64()), request.mimeType());
    }

    @PostMapping("/folders")
    public Map<String, String> findOrCreateFolder(@Valid @RequestBody FolderRequest request) {
        requireDriveAuth();
        return Map.of("folderId", drive.findOrCreateFolder(request.parentFolderId(), request.name()));
    }

    @GetMapping("/companies")
    public List<CompanyFolder> companies() {
        requireDriveAuth();
        return conventions.listCompanyFolders(properties.rootFolderId());
    }

    @PostMapping("/company-files")
    public NamedFolderUpload uploadCompanyFile(@Valid @RequestBody CompanyFileRequest request) {
        requireDriveAuth();
        return conventions.uploadFileToNamedFolder(properties.rootFolderId(), request.companyName(),
                request.fileName(), decode(request.contentBase64()), request.mimeType());
    }

    @PostMapping("/chat-backups")
    public NamedFolderUpload uploadChatBackup(@Valid @RequestBody ChatBackupRequest request) {
        requireDriveAuth();
        return conventions.uploadSessionBackup(properties.rootFolderId(), request.sessionId(),
                decode(request.contentBase64()), Instant.now());
    }

    private void requireDriveAuth() {
        if (!auth.isAuthenticated()) {
            throw new DriveNotAuthenticatedException();
        }
    }

    private static byte[] decode(String base64) {
        try {
            return Base64.getDecoder().decode(base64);
        }
        catch (IllegalArgumentException ex) {
            throw new BloomException(ErrorCode.VALIDATION_FAILED, "contentBase64 is not valid Base64");
        }
    }
}
