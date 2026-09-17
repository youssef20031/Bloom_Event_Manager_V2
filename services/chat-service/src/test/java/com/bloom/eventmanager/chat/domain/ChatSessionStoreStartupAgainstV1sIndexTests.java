package com.bloom.eventmanager.chat.domain;

import org.bson.Document;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.model.IndexOptions;

/**
 * Reproduces chat-service.yml's documented primary deployment mode: pointing
 * at a {@code chatdb} that V1's Mongoose already populated, whose
 * {@code chatsessions} collection already carries a unique index on
 * {@code sessionId} named {@code sessionId_1} — MongoDB's default name for an
 * unnamed {@code createIndex({sessionId:1},{unique:true})} call, which is all
 * Mongoose ever issues.
 *
 * <p>The index is seeded directly against the container in a static
 * {@code @BeforeAll}, which JUnit Jupiter runs after the {@code @Container}
 * field has started (a {@link Testcontainers} {@code BeforeAllCallback}) but
 * before {@code SpringExtension} builds the {@code ApplicationContext} (which
 * happens later, while preparing the test instance) — so the collection and
 * its Mongoose-style index exist before Spring ever sees this database, the
 * same way they would for a service pointed at V1's real {@code chatdb}.
 *
 * <p>Before the fix, {@code auto-index-creation=true} (forced below, since
 * this simulates a deployment where it is on — chat-service.yml's own
 * default is checked separately) makes Spring Data build its own index on
 * {@code sessionId} at context refresh. An unnamed {@code @Indexed} resolves
 * to the field's dot-path, "sessionId", which collides with the pre-existing
 * "sessionId_1" under MongoDB error 85 (IndexOptionsConflict);
 * {@code MongoPersistentEntityIndexCreator} rethrows that as a
 * {@code DataIntegrityViolationException}, aborting context refresh outright.
 * Getting as far as {@link #contextStartsAgainstACollectionMongooseAlreadyIndexed()}
 * running at all is the assertion: a broken context never reaches it.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "spring.config.import=",
        "eureka.client.enabled=false",
        "spring.cloud.discovery.enabled=false",
        "bloom.security.internal-token=test-token",
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
        // Forced on deliberately: this is what actually breaks under the old,
        // unnamed @Indexed(unique = true). chat-service.yml turns this off in
        // production (ChatSessionIndexInitializer builds the index instead),
        // but @Indexed(useGeneratedName = true) must also be correct on its
        // own, in case a deployment ever re-enables it.
        "spring.data.mongodb.auto-index-creation=true"
})
class ChatSessionStoreStartupAgainstV1sIndexTests {

    // 8.2, not 8.0: MongoDB 8.0 refuses to start on Linux kernel 6.19+
    // (SERVER-121912), which Docker Desktop's VM kernel is well past. Same
    // pin as ChatSessionStoreIntegrationTests and deploy/docker-compose.yml.
    @Container
    @ServiceConnection
    static MongoDBContainer mongo = new MongoDBContainer("mongo:8.2");

    @BeforeAll
    static void seedV1sMongooseIndex() {
        // @ServiceConnection wires Spring to MongoDBContainer#getReplicaSetUrl(),
        // whose database segment defaults to "test" — the same database Spring
        // will use once the context starts, so the index must be seeded there.
        try (MongoClient client = MongoClients.create(mongo.getConnectionString())) {
            client.getDatabase("test").getCollection("chatsessions")
                    .createIndex(new Document("sessionId", 1), new IndexOptions().unique(true).name("sessionId_1"));
        }
    }

    @Test
    void contextStartsAgainstACollectionMongooseAlreadyIndexed() {
        // Intentionally empty: reaching this point means the ApplicationContext
        // refreshed successfully. A naming conflict aborts context refresh
        // before any @Test method in this class would ever run.
    }
}
