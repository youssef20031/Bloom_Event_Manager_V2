package com.bloom.eventmanager.storage.drive;

import java.util.List;

/** V1's IStorageService file operations. Everything here needs an authenticated Credential. */
public interface DriveClient {

    List<StorageFile> listFolders(String folderId);

    /** {@code mimeTypeFilter} null lists every non-folder file. */
    List<StorageFile> listFiles(String folderId, String mimeTypeFilter);

    /** Names of files in subfolders are prefixed with their relative path, as V1 did. */
    List<StorageFile> listFilesRecursive(String rootFolderId, String mimeTypeFilter);

    StorageFileInfo fileInfo(String fileId);

    byte[] download(String fileId);

    UploadResult upload(String folderId, String fileName, byte[] content, String mimeType);

    String createFolder(String parentId, String name);

    String findOrCreateFolder(String parentId, String name);
}
