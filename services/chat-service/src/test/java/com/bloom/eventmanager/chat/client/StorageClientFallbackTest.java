package com.bloom.eventmanager.chat.client;

import org.junit.jupiter.api.Test;

import com.bloom.eventmanager.chat.client.dto.ChatBackupRequest;
import com.bloom.eventmanager.common.error.ServiceUnavailableException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StorageClientFallbackTest {

    private final StorageClientFallback fallback = new StorageClientFallback();

    @Test
    void uploadChatBackupThrowsServiceUnavailableSoTheBrowserSeesAClean503() {
        assertThatThrownBy(() -> fallback.uploadChatBackup(new ChatBackupRequest("s1", "YQ==")))
                .isInstanceOf(ServiceUnavailableException.class).hasMessageContaining("storage-service");
    }
}
