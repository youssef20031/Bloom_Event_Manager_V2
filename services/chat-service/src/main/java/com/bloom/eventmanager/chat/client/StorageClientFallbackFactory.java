package com.bloom.eventmanager.chat.client;

import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import com.bloom.eventmanager.chat.client.dto.ChatBackupRequest;
import com.bloom.eventmanager.chat.client.dto.ChatBackupResponse;
import com.bloom.eventmanager.common.error.ServiceUnavailableException;
import com.bloom.eventmanager.common.feign.FeignFallbacks;

/**
 * Runs when storage-service is down or its breaker is open. chat-service is
 * called directly by the browser, so throwing (rather than returning a fake
 * backup result) surfaces as a clean 503 ProblemDetail instead of a hung
 * request or a 502 — the caller sees plainly that the backup did not happen.
 *
 * <p>A {@code FallbackFactory} rather than a plain fallback because the invocation
 * handler routes <em>every</em> throwable here, including the {@code FeignException}
 * raised for a well-formed 4xx. Specifically, storage-service answers 401 whenever
 * Drive consent has not been given yet; {@link FeignFallbacks#failure} hands that back
 * unchanged so {@code SessionController.backup}'s
 * {@code catch (FeignException.Unauthorized)} still raises DRIVE_NOT_AUTHENTICATED with
 * the {@code authUrl} the SPA uses to prompt a reconnect, instead of a misleading 503.
 */
@Component
public class StorageClientFallbackFactory implements FallbackFactory<StorageClient> {

    @Override
    public StorageClient create(Throwable cause) {
        return new StorageClient() {

            @Override
            public ChatBackupResponse uploadChatBackup(ChatBackupRequest request) {
                throw failure(cause);
            }
        };
    }

    private static RuntimeException failure(Throwable cause) {
        return FeignFallbacks.failure(cause,
                () -> new ServiceUnavailableException(FeignFallbacks.unavailableMessage("storage-service", cause), cause));
    }
}
