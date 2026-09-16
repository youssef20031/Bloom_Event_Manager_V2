package com.bloom.eventmanager.chat.api;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import feign.FeignException;
import feign.Request;

import com.bloom.eventmanager.chat.client.StorageClient;
import com.bloom.eventmanager.chat.client.dto.ChatBackupRequest;
import com.bloom.eventmanager.chat.client.dto.ChatBackupResponse;
import com.bloom.eventmanager.chat.domain.ChatMessage;
import com.bloom.eventmanager.chat.domain.ChatSessionDocument;
import com.bloom.eventmanager.chat.domain.ChatSessionStore;
import com.bloom.eventmanager.chat.pdf.ChatPdfRenderer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SessionController.class)
class SessionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ChatSessionStore store;

    @MockitoBean
    private ChatPdfRenderer renderer;

    @MockitoBean
    private StorageClient storage;

    private static ChatSessionDocument session(String id, String... contents) {
        ChatSessionDocument s = new ChatSessionDocument();
        s.setSessionId(id);
        s.setCreatedAt(Instant.parse("2026-09-15T10:00:00Z"));
        s.setUpdatedAt(Instant.parse("2026-09-15T10:05:00Z"));
        s.setMessages(java.util.Arrays.stream(contents)
                .map(c -> new ChatMessage("user", c, Instant.now(), List.of(), null)).toList());
        return s;
    }

    @Test
    void listsSessionsWithOnlyTheFirstMessageLikeV1sProjection() throws Exception {
        when(store.summaries()).thenReturn(List.of(session("s1", "first", "second")));

        mockMvc.perform(get("/api/v1/chat/sessions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.sessions[0].sessionId").value("s1"))
                .andExpect(jsonPath("$.sessions[0].messages.length()").value(1))
                .andExpect(jsonPath("$.sessions[0].messages[0].content").value("first"))
                .andExpect(jsonPath("$.sessions[0].createdAt").value("2026-09-15T10:00:00Z"));
    }

    @Test
    void returnsAFullSessionOr404() throws Exception {
        when(store.find("s1")).thenReturn(Optional.of(session("s1", "a", "b")));
        when(store.find("nope")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/chat/sessions/s1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.session.messages.length()").value(2));
        mockMvc.perform(get("/api/v1/chat/sessions/nope"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));
    }

    @Test
    void exportsAPdf() throws Exception {
        when(store.find("s1")).thenReturn(Optional.of(session("s1", "a")));
        when(renderer.render(any())).thenReturn("%PDF-1.7 fake".getBytes());

        mockMvc.perform(post("/api/v1/chat/sessions/s1/pdf"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(content().bytes("%PDF-1.7 fake".getBytes()));
    }

    @Test
    void backsUpToDriveViaStorageService() throws Exception {
        when(store.find("session_1_abcdefg")).thenReturn(Optional.of(session("session_1_abcdefg", "a")));
        when(renderer.render(any())).thenReturn(new byte[] { 1, 2 });
        when(storage.uploadChatBackup(argThat(r -> r != null && r.sessionId().equals("session_1_abcdefg")
                && r.contentBase64().equals(java.util.Base64.getEncoder().encodeToString(new byte[] { 1, 2 })))))
                .thenReturn(new ChatBackupResponse("folder", "file", "link"));

        mockMvc.perform(post("/api/v1/chat/sessions/session_1_abcdefg/backup"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.message").value("Chat backup created successfully"))
                .andExpect(jsonPath("$.drive.fileId").value("file"))
                .andExpect(jsonPath("$.drive.webViewLink").value("link"));
    }

    /**
     * V1 parity: the React app's re-auth handling reads {@code errorCode} and
     * {@code authUrl} off a 401 to redirect the user through Drive OAuth again.
     * Nothing else in this module exercises the catch-order in
     * {@code SessionController.backup} that turns storage-service's 401 into
     * this shape, so an accidental swap with the generic
     * {@code ExternalServiceException} catch, or a {@code properties()} override
     * that drops {@code authUrl}, would otherwise pass every other test here.
     */
    @Test
    void reraisesStorageServicesDriveAuthFailureInV1sShape() throws Exception {
        when(store.find("s1")).thenReturn(Optional.of(session("s1", "a")));
        when(renderer.render(any())).thenReturn(new byte[] { 1, 2 });
        Request request = Request.create("POST", "/api/v1/storage/chat-backups", Map.of(), new byte[0],
                StandardCharsets.UTF_8);
        when(storage.uploadChatBackup(any()))
                .thenThrow(new FeignException.Unauthorized("Unauthorized", request, new byte[0], Map.of()));

        mockMvc.perform(post("/api/v1/chat/sessions/s1/backup"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("DRIVE_NOT_AUTHENTICATED"))
                .andExpect(jsonPath("$.authUrl").value("/api/v1/rag/sync/auth?force=true"));
    }

    @Test
    void searchesAndCountsMentions() throws Exception {
        // proposal-service (Task 5) deserializes this raw array as
        // List<ChatSession(sessionId, messages, createdAt, updatedAt)>; nothing
        // else in this repo can catch a field being renamed, dropped, or the
        // root becoming a wrapped object, since that consumer doesn't exist yet.
        when(store.mentioning("Acme")).thenReturn(List.of(session("s1", "Acme plan")));
        when(store.countMentioning("Acme")).thenReturn(1L);

        mockMvc.perform(get("/api/v1/chat/sessions/search").param("mentions", "Acme"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].sessionId").value("s1"))
                .andExpect(jsonPath("$[0].messages.length()").value(1))
                .andExpect(jsonPath("$[0].messages[0].content").value("Acme plan"))
                .andExpect(jsonPath("$[0].createdAt").value("2026-09-15T10:00:00Z"))
                .andExpect(jsonPath("$[0].updatedAt").value("2026-09-15T10:05:00Z"))
                .andExpect(jsonPath("$[0].id").doesNotExist());
        mockMvc.perform(get("/api/v1/chat/sessions/count").param("mentions", "Acme"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1));
    }
}
