package com.bloom.eventmanager.chat.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

/** V1's MongoChatRepository. */
@Service
public class ChatSessionStore {

    private final ChatSessionRepository repository;

    public ChatSessionStore(ChatSessionRepository repository) {
        this.repository = repository;
    }

    public Optional<ChatSessionDocument> find(String sessionId) {
        return repository.findBySessionId(sessionId);
    }

    public List<ChatMessage> recentMessages(String sessionId, int limit) {
        return repository.findBySessionId(sessionId)
                .map(session -> {
                    List<ChatMessage> all = session.getMessages();
                    return List.copyOf(all.subList(Math.max(0, all.size() - limit), all.size()));
                })
                .orElse(List.of());
    }

    /** Upsert: creates the session on first append (V1's findOneAndUpdate with upsert). */
    public void append(String sessionId, List<ChatMessage> messages) {
        Instant now = Instant.now();
        ChatSessionDocument session = repository.findBySessionId(sessionId).orElseGet(() -> {
            ChatSessionDocument fresh = new ChatSessionDocument();
            fresh.setSessionId(sessionId);
            fresh.setCreatedAt(now);
            return fresh;
        });
        List<ChatMessage> updated = new ArrayList<>(session.getMessages());
        for (ChatMessage message : messages) {
            updated.add(message.timestamp() == null
                    ? new ChatMessage(message.role(), message.content(), now, message.sources(), message.thoughts())
                    : message);
        }
        session.setMessages(updated);
        session.setUpdatedAt(now);
        repository.save(session);
    }

    public List<ChatSessionDocument> summaries() {
        return repository.findAll(Sort.by(Sort.Direction.DESC, "updatedAt"));
    }

    public List<ChatSessionDocument> mentioning(String companyName) {
        return repository.findMentioning(Pattern.quote(companyName));
    }

    @Cacheable(cacheNames = "chatCountForCompany", key = "#companyName.toLowerCase()")
    public long countMentioning(String companyName) {
        return repository.countMentioning(Pattern.quote(companyName));
    }
}
