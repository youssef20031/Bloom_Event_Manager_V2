package com.bloom.eventmanager.chat.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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

    /**
     * Clears documents without dropping the collection: a drop also drops the
     * unique {@code sessionId} index that auto-index-creation built once at
     * context startup, and without it a concurrent append racing on a brand-new
     * session id has nothing stopping MongoDB from inserting two documents.
     */
    @BeforeEach
    void clean() {
        template.getCollection("chatsessions").deleteMany(new Document());
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
        store.append("s4", List.of(msg("user", "Loves A.C.M.E (Corp) swag")));

        assertThat(store.mentioning("Acme Corp")).extracting(ChatSessionDocument::getSessionId)
                .containsExactlyInAnyOrder("s1", "s2");
        assertThat(store.countMentioning("acme corp")).isEqualTo(2);

        // True positive: a company name that is itself built from regex metacharacters
        // still matches the session that genuinely mentions it, once escaped.
        assertThat(store.mentioning("A.C.M.E (Corp)")).extracting(ChatSessionDocument::getSessionId)
                .containsExactly("s4");
        assertThat(store.countMentioning("A.C.M.E (Corp)")).isEqualTo(1);

        // False positive guard: metacharacters are escaped, not interpreted, so a
        // differently-shaped metacharacter string that no session mentions matches nothing.
        assertThat(store.countMentioning("Glob.ex (Ltd)")).isZero();
    }

    /**
     * Pins the atomicity of {@link ChatSessionStore#append}: it must be a single
     * server-side findAndModify ($push/$setOnInsert), not a read-modify-write
     * save(), or two concurrent appends to the same session race and the second
     * save() overwrites the first wholesale, silently dropping its messages.
     */
    @Test
    void concurrentAppendsToTheSameSessionDoNotLoseMessages() throws Exception {
        int threadCount = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threadCount; i++) {
                int threadId = i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    store.append("concurrent", List.of(msg("user", "t" + threadId + "-a"),
                            msg("model", "t" + threadId + "-b")));
                    return null;
                }));
            }
            ready.await();
            start.countDown();
            for (Future<?> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdown();
        }

        List<String> contents = store.find("concurrent").orElseThrow().getMessages().stream()
                .map(ChatMessage::content).toList();

        List<String> expected = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            expected.add("t" + i + "-a");
            expected.add("t" + i + "-b");
        }
        assertThat(contents).containsExactlyInAnyOrderElementsOf(expected);
    }

    private static ChatMessage msg(String role, String content) {
        return new ChatMessage(role, content, Instant.now(), List.of(), null);
    }
}
