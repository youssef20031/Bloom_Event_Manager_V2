package com.bloom.eventmanager.chat.domain;

import java.time.Instant;
import java.util.List;

import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "spring.config.import=",
        "eureka.client.enabled=false",
        "spring.cloud.discovery.enabled=false",
        "spring.data.mongodb.auto-index-creation=true"
})
class ChatSessionStoreIntegrationTests {

    // 8.2, not 8.0: MongoDB 8.0 refuses to start on Linux kernel 6.19+
    // (SERVER-121912), which Docker Desktop's VM kernel is well past.
    // Same pin as storage-service's MongoTokenStoreIntegrationTests and
    // deploy/docker-compose.yml.
    @Container
    @ServiceConnection
    static MongoDBContainer mongo = new MongoDBContainer("mongo:8.2");

    @Autowired
    private ChatSessionStore store;

    @Autowired
    private MongoTemplate template;

    @BeforeEach
    void clean() {
        template.getCollection("chatsessions").drop();
    }

    @Test
    void appendCreatesTheSessionThenAppendsInOrder() {
        store.append("s1", List.of(msg("user", "hi"), msg("model", "hello")));
        store.append("s1", List.of(msg("user", "more")));

        ChatSessionDocument session = store.find("s1").orElseThrow();
        assertThat(session.getMessages()).extracting(ChatMessage::content).containsExactly("hi", "hello", "more");
        assertThat(session.getCreatedAt()).isNotNull();
        assertThat(session.getUpdatedAt()).isAfterOrEqualTo(session.getCreatedAt());
        assertThat(template.getCollection("chatsessions").countDocuments()).isEqualTo(1);
    }

    @Test
    void writesV1sMongooseShape() {
        store.append("s1", List.of(new ChatMessage("model", "answer", Instant.parse("2026-09-15T10:00:00Z"),
                List.of("guide.pdf"), "thinking")));

        Document raw = template.getCollection("chatsessions").find().first();
        assertThat(raw).isNotNull();
        assertThat(raw.getString("sessionId")).isEqualTo("s1");
        Document message = raw.getList("messages", Document.class).get(0);
        assertThat(message.getString("role")).isEqualTo("model");
        assertThat(message.getList("sources", String.class)).containsExactly("guide.pdf");
        assertThat(message.getString("thoughts")).isEqualTo("thinking");
        assertThat(message.getDate("timestamp")).isNotNull();
    }

    @Test
    void readsASessionWrittenByV1() {
        template.getCollection("chatsessions").insertOne(new Document("sessionId", "session_1_abc")
                .append("messages", List.of(new Document("role", "user").append("content", "Plan for Acme")
                        .append("timestamp", new java.util.Date()).append("sources", List.of())))
                .append("createdAt", new java.util.Date()).append("updatedAt", new java.util.Date()));

        assertThat(store.recentMessages("session_1_abc", 6)).singleElement()
                .satisfies(m -> assertThat(m.content()).isEqualTo("Plan for Acme"));
    }

    @Test
    void recentMessagesReturnsTheTail() {
        store.append("s1", List.of(msg("user", "1"), msg("model", "2"), msg("user", "3"), msg("model", "4")));

        assertThat(store.recentMessages("s1", 2)).extracting(ChatMessage::content).containsExactly("3", "4");
        assertThat(store.recentMessages("missing", 2)).isEmpty();
    }

    @Test
    void summariesAreNewestFirst() throws InterruptedException {
        store.append("old", List.of(msg("user", "a")));
        Thread.sleep(5);
        store.append("new", List.of(msg("user", "b")));

        assertThat(store.summaries()).extracting(ChatSessionDocument::getSessionId).containsExactly("new", "old");
    }

    @Test
    void findsAndCountsSessionsMentioningACompanyCaseInsensitively() {
        store.append("s1", List.of(msg("user", "Ideas for ACME Corp offsite")));
        store.append("s2", List.of(msg("user", "Something else"), msg("model", "acme corp likes puzzles")));
        store.append("s3", List.of(msg("user", "Globex")));

        assertThat(store.mentioning("Acme Corp")).extracting(ChatSessionDocument::getSessionId)
                .containsExactlyInAnyOrder("s1", "s2");
        assertThat(store.countMentioning("acme corp")).isEqualTo(2);
        assertThat(store.countMentioning("A.C.M.E (Corp)")).isZero(); // regex metacharacters are escaped
    }

    private static ChatMessage msg(String role, String content) {
        return new ChatMessage(role, content, Instant.now(), List.of(), null);
    }
}
