package com.bloom.eventmanager.chat.domain;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

public interface ChatSessionRepository extends MongoRepository<ChatSessionDocument, String> {

    Optional<ChatSessionDocument> findBySessionId(String sessionId);

    List<ChatSessionDocument> findAll(Sort sort);

    /** V1: ChatSession.countDocuments({ "messages.content": { $regex, $options: "i" } }). */
    @Query("{ 'messages.content': { $regex: ?0, $options: 'i' } }")
    List<ChatSessionDocument> findMentioning(String escapedRegex);

    @Query(value = "{ 'messages.content': { $regex: ?0, $options: 'i' } }", count = true)
    long countMentioning(String escapedRegex);
}
