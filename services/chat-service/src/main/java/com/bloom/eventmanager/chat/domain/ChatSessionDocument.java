package com.bloom.eventmanager.chat.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import com.fasterxml.jackson.annotation.JsonIgnore;

/** V1's Mongoose ChatSession model: collection "chatsessions". */
@Document(collection = "chatsessions")
public class ChatSessionDocument {

    @Id
    @JsonIgnore
    private String id;

    /**
     * Created by {@link ChatSessionIndexInitializer}, not by this annotation:
     * auto-index-creation is off (see chat-service.yml) so an unreachable
     * MongoDB cannot abort startup, and so a fresh index build cannot collide
     * with V1's Mongoose-created one. {@code useGeneratedName = true} makes
     * the annotation itself agree with that initializer if auto-index-creation
     * is ever turned back on: an unnamed {@code @Indexed} otherwise resolves
     * to the field's dot-path ("sessionId"), and MongoDB rejects a same-key
     * index registered under a different name from V1's Mongoose-generated
     * "sessionId_1" (error 85, IndexOptionsConflict) — aborting context
     * refresh with a DataIntegrityViolationException. useGeneratedName makes
     * the server generate "sessionId_1" itself, matching V1's name exactly.
     */
    @Indexed(unique = true, useGeneratedName = true)
    private String sessionId;

    private List<ChatMessage> messages = new ArrayList<>();

    private Instant createdAt;

    private Instant updatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public List<ChatMessage> getMessages() { return messages; }
    public void setMessages(List<ChatMessage> messages) { this.messages = messages; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
