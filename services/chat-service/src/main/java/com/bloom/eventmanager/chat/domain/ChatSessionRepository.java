package com.bloom.eventmanager.chat.domain;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

public interface ChatSessionRepository extends MongoRepository<ChatSessionDocument, String> {

    Optional<ChatSessionDocument> findBySessionId(String sessionId);

    List<ChatSessionDocument> findAll(Sort sort);

    /**
     * V1: {@code ChatSession.find({}, {sessionId, createdAt, messages: {$slice: 1}}).sort({updatedAt: -1})}.
     * Projects every session down to its first message server-side instead of
     * deserialising the whole document just to discard all but one message in
     * {@code SessionController} — against a real {@code chatdb}, a session's
     * message array can be arbitrarily large, and this endpoint loads every
     * session on every sidebar render.
     */
    @Query(value = "{}", fields = "{ 'sessionId': 1, 'createdAt': 1, 'updatedAt': 1, 'messages': { $slice: 1 } }")
    List<ChatSessionDocument> findSummaries(Sort sort);

    /** V1: ChatSession.countDocuments({ "messages.content": { $regex, $options: "i" } }). */
    @Query("{ 'messages.content': { $regex: ?0, $options: 'i' } }")
    List<ChatSessionDocument> findMentioning(String escapedRegex);

    @Query(value = "{ 'messages.content': { $regex: ?0, $options: 'i' } }", count = true)
    long countMentioning(String escapedRegex);
}
