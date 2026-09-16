package com.bloom.eventmanager.chat.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/** V1's MongoChatRepository. */
@Service
public class ChatSessionStore {

    private final ChatSessionRepository repository;
    private final MongoTemplate mongoTemplate;

    public ChatSessionStore(ChatSessionRepository repository, MongoTemplate mongoTemplate) {
        this.repository = repository;
        this.mongoTemplate = mongoTemplate;
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

    /**
     * Atomic upsert (V1's findOneAndUpdate with {@code $push} and upsert): a single
     * server-side findAndModify appends the messages and stamps {@code updatedAt}
     * (only {@code $setOnInsert} stamps {@code createdAt}, so it is written once).
     * A read-modify-write here — find, mutate the list in memory, save() the whole
     * document — would let two concurrent appends to the same session race, with
     * the second save() overwriting the first wholesale and silently dropping its
     * messages; findAndModify's {@code $push} is applied atomically per document,
     * so that cannot happen.
     */
    public void append(String sessionId, List<ChatMessage> messages) {
        Instant now = Instant.now();
        List<ChatMessage> stamped = messages.stream()
                .map(message -> message.timestamp() == null
                        ? new ChatMessage(message.role(), message.content(), now, message.sources(), message.thoughts())
                        : message)
                .toList();

        Query query = Query.query(Criteria.where("sessionId").is(sessionId));
        Update update = new Update()
                .push("messages").each(stamped.toArray())
                .set("updatedAt", now)
                .setOnInsert("sessionId", sessionId)
                .setOnInsert("createdAt", now);

        mongoTemplate.findAndModify(query, update, FindAndModifyOptions.options().upsert(true),
                ChatSessionDocument.class);
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
