package com.bloom.eventmanager.chat.api;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.bloom.eventmanager.chat.client.StorageClient;
import com.bloom.eventmanager.chat.client.dto.ChatBackupRequest;
import com.bloom.eventmanager.chat.client.dto.ChatBackupResponse;
import com.bloom.eventmanager.chat.domain.ChatSessionDocument;
import com.bloom.eventmanager.chat.domain.ChatSessionStore;
import com.bloom.eventmanager.chat.pdf.ChatPdfRenderer;
import com.bloom.eventmanager.common.error.BloomException;
import com.bloom.eventmanager.common.error.ExternalServiceException;
import com.bloom.eventmanager.common.error.NotFoundException;

@RestController
@RequestMapping("/api/v1/chat/sessions")
public class SessionController {

    private final ChatSessionStore store;
    private final ChatPdfRenderer renderer;
    private final StorageClient storage;

    public SessionController(ChatSessionStore store, ChatPdfRenderer renderer, StorageClient storage) {
        this.store = store;
        this.renderer = renderer;
        this.storage = storage;
    }

    /** V1: ChatSession.find({}, {sessionId, createdAt, messages: {$slice: 1}}).sort({updatedAt: -1}). */
    @GetMapping
    public Map<String, Object> sessions() {
        List<Map<String, Object>> summaries = store.summaries().stream().map(session -> {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("sessionId", session.getSessionId());
            summary.put("createdAt", session.getCreatedAt());
            summary.put("updatedAt", session.getUpdatedAt());
            summary.put("messages", session.getMessages().isEmpty() ? List.of() : List.of(session.getMessages().get(0)));
            return summary;
        }).toList();
        return Map.of("status", "success", "sessions", summaries);
    }

    @GetMapping("/search")
    public List<ChatSessionDocument> search(@RequestParam("mentions") String mentions) {
        return store.mentioning(mentions);
    }

    @GetMapping("/count")
    public Map<String, Long> count(@RequestParam("mentions") String mentions) {
        return Map.of("count", store.countMentioning(mentions));
    }

    @GetMapping("/{sessionId}")
    public Map<String, Object> session(@PathVariable String sessionId) {
        return Map.of("status", "success", "session", require(sessionId));
    }

    @PostMapping(value = "/{sessionId}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> pdf(@PathVariable String sessionId) {
        byte[] pdf = renderer.render(require(sessionId));
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header("Content-Disposition", "attachment; filename=\"Chat_" + sessionId + ".pdf\"")
                .body(pdf);
    }

    @PostMapping("/{sessionId}/backup")
    public Map<String, Object> backup(@PathVariable String sessionId) {
        byte[] pdf = renderer.render(require(sessionId));
        ChatBackupResponse drive;
        try {
            drive = storage.uploadChatBackup(new ChatBackupRequest(sessionId, Base64.getEncoder().encodeToString(pdf)));
        }
        catch (feign.FeignException.Unauthorized unauthorized) {
            throw new DriveNotAuthenticated();
        }
        catch (BloomException ex) {
            throw ex;
        }
        catch (RuntimeException ex) {
            throw new ExternalServiceException("Chat backup upload failed", ex);
        }
        return Map.of("status", "success", "message", "Chat backup created successfully", "drive", drive);
    }

    private ChatSessionDocument require(String sessionId) {
        return store.find(sessionId).orElseThrow(() -> new NotFoundException("Session not found"));
    }

    /** storage-service said Drive is not authenticated; re-raise in V1's shape for the SPA. */
    static final class DriveNotAuthenticated extends com.bloom.eventmanager.common.error.BloomException {
        DriveNotAuthenticated() {
            super(com.bloom.eventmanager.common.error.ErrorCode.DRIVE_NOT_AUTHENTICATED, "Google Drive not authenticated.");
        }

        @Override
        public Map<String, Object> properties() {
            return Map.of("authUrl", "/api/v1/rag/sync/auth?force=true",
                    "error", "Google Drive not authenticated.", "message", "Google Drive not authenticated.");
        }
    }
}
