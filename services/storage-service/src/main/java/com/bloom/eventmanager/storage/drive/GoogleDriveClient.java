package com.bloom.eventmanager.storage.drive;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import com.google.api.client.http.ByteArrayContent;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.FileList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.stereotype.Component;

import com.bloom.eventmanager.common.error.BloomException;
import com.bloom.eventmanager.common.error.ExternalServiceException;
import com.bloom.eventmanager.common.feign.FeignFallbacks;
import com.bloom.eventmanager.storage.oauth.DriveAuthManager;

/** V1's GoogleDriveFileOperations over the Drive v3 Java client. */
@Component
public class GoogleDriveClient implements DriveClient {

    private static final Logger log = LoggerFactory.getLogger(GoogleDriveClient.class);
    private static final String FOLDER_MIME = "application/vnd.google-apps.folder";
    private static final String FILE_FIELDS = "files(id, name, mimeType, modifiedTime, size)";

    private final DriveAuthManager auth;
    private final CircuitBreakerFactory<?, ?> breakers;
    private final NetHttpTransport transport = new NetHttpTransport();
    private final GsonFactory jsonFactory = GsonFactory.getDefaultInstance();

    public GoogleDriveClient(DriveAuthManager auth, CircuitBreakerFactory<?, ?> breakers) {
        this.auth = auth;
        this.breakers = breakers;
    }

    private Drive drive() {
        return new Drive.Builder(transport, jsonFactory, auth.credential())
                .setApplicationName("Bloom Event Manager")
                .build();
    }

    @Override
    public List<StorageFile> listFolders(String folderId) {
        return call("list folders", () -> toStorageFiles(drive().files().list()
                .setQ("'" + folderId + "' in parents and mimeType='" + FOLDER_MIME + "' and trashed=false")
                .setFields(FILE_FIELDS)
                .setOrderBy("name")
                .execute()));
    }

    @Override
    public List<StorageFile> listFiles(String folderId, String mimeTypeFilter) {
        String mimeClause = mimeTypeFilter != null
                ? " and mimeType='" + mimeTypeFilter + "'"
                : " and mimeType!='" + FOLDER_MIME + "'";
        return call("list files", () -> toStorageFiles(drive().files().list()
                .setQ("'" + folderId + "' in parents and trashed=false" + mimeClause)
                .setFields(FILE_FIELDS)
                .setOrderBy("modifiedTime desc")
                .execute()));
    }

    @Override
    public List<StorageFile> listFilesRecursive(String rootFolderId, String mimeTypeFilter) {
        List<StorageFile> all = new ArrayList<>();
        scan(rootFolderId, "", mimeTypeFilter, all);
        log.info("Drive: found {} files under {} (including subfolders)", all.size(), rootFolderId);
        return all;
    }

    private void scan(String folderId, String path, String mimeTypeFilter, List<StorageFile> into) {
        for (StorageFile file : listFiles(folderId, mimeTypeFilter)) {
            String name = path.isEmpty() ? file.name() : path + "/" + file.name();
            into.add(new StorageFile(file.id(), name, file.mimeType(), file.modifiedTime(), file.size()));
        }
        for (StorageFile sub : listFolders(folderId)) {
            scan(sub.id(), path.isEmpty() ? sub.name() : path + "/" + sub.name(), mimeTypeFilter, into);
        }
    }

    @Override
    public StorageFileInfo fileInfo(String fileId) {
        return call("file info", () -> {
            File file = drive().files().get(fileId).setFields("id, name, modifiedTime, md5Checksum").execute();
            return new StorageFileInfo(file.getId(), file.getName(), rfc3339(file), file.getMd5Checksum());
        });
    }

    @Override
    public byte[] download(String fileId) {
        return call("download", () -> {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            drive().files().get(fileId).executeMediaAndDownloadTo(out);
            return out.toByteArray();
        });
    }

    @Override
    public UploadResult upload(String folderId, String fileName, byte[] content, String mimeType) {
        return call("upload", () -> {
            File metadata = new File().setName(fileName).setParents(List.of(folderId));
            File created = drive().files().create(metadata, new ByteArrayContent(mimeType, content))
                    .setFields("id, webViewLink").execute();
            log.info("Uploaded \"{}\" to Drive folder {}", fileName, folderId);
            return new UploadResult(created.getId(), created.getWebViewLink());
        });
    }

    @Override
    public String createFolder(String parentId, String name) {
        return call("create folder", () -> {
            File metadata = new File().setName(name).setMimeType(FOLDER_MIME).setParents(List.of(parentId));
            return drive().files().create(metadata).setFields("id").execute().getId();
        });
    }

    @Override
    public String findOrCreateFolder(String parentId, String name) {
        String escaped = name.replace("'", "\\'");
        FileList existing = call("find folder", () -> drive().files().list()
                .setQ("name='" + escaped + "' and '" + parentId + "' in parents and mimeType='" + FOLDER_MIME + "' and trashed=false")
                .setFields("files(id, name)")
                .execute());
        if (existing.getFiles() != null && !existing.getFiles().isEmpty()) {
            return existing.getFiles().get(0).getId();
        }
        return createFolder(parentId, name);
    }

    private static List<StorageFile> toStorageFiles(FileList list) {
        List<StorageFile> files = new ArrayList<>();
        if (list.getFiles() == null) {
            return files;
        }
        for (File file : list.getFiles()) {
            files.add(new StorageFile(file.getId(), file.getName(), file.getMimeType(), rfc3339(file), file.getSize()));
        }
        return files;
    }

    /** Same RFC 3339 string V1 received from the Node client, so stored values compare equal. */
    private static String rfc3339(File file) {
        return file.getModifiedTime() == null ? null : file.getModifiedTime().toStringRfc3339();
    }

    private <T> T call(String operation, DriveCall<T> call) {
        return breakers.create("drive").run(
                () -> {
                    try {
                        return call.run();
                    }
                    catch (IOException ex) {
                        auth.handleAuthError(ex);
                        throw new ExternalServiceException("Google Drive " + operation + " failed", ex);
                    }
                },
                failure -> {
                    // A failure that already carries our own diagnosis passes straight through:
                    // the IOException wrapped above, or DriveNotAuthenticatedException from
                    // credential(), whose 401 and authUrl send the SPA to re-auth. Anything
                    // else is described by what actually happened -- "circuit open" only
                    // when the breaker rejected the call, "timed out" for the time limiter.
                    if (failure instanceof BloomException diagnosed) {
                        throw diagnosed;
                    }
                    throw new ExternalServiceException(
                            FeignFallbacks.unavailableMessage("Google Drive " + operation, failure), failure);
                });
    }

    @FunctionalInterface
    private interface DriveCall<T> {
        T run() throws IOException;
    }
}
