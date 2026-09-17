package com.bloom.eventmanager.chat.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import com.bloom.eventmanager.chat.client.dto.ChatBackupRequest;
import com.bloom.eventmanager.chat.client.dto.ChatBackupResponse;

@FeignClient(name = "storage-service", fallback = StorageClientFallback.class)
public interface StorageClient {

    @PostMapping("/api/v1/storage/chat-backups")
    ChatBackupResponse uploadChatBackup(@RequestBody ChatBackupRequest request);
}
