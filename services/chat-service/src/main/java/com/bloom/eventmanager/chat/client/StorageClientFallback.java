package com.bloom.eventmanager.chat.client;

import org.springframework.stereotype.Component;

import com.bloom.eventmanager.chat.client.dto.ChatBackupRequest;
import com.bloom.eventmanager.chat.client.dto.ChatBackupResponse;
import com.bloom.eventmanager.common.error.ServiceUnavailableException;

/**
 * Runs when storage-service is down or its breaker is open. chat-service is
 * called directly by the browser, so throwing (rather than returning a fake
 * backup result) surfaces as a clean 503 ProblemDetail instead of a hung
 * request or a 502 — the caller sees plainly that the backup did not happen.
 */
@Component
public class StorageClientFallback implements StorageClient {

    @Override
    public ChatBackupResponse uploadChatBackup(ChatBackupRequest request) {
        throw new ServiceUnavailableException("storage-service unavailable (circuit open)", null);
    }
}
