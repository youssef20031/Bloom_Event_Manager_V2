package com.bloom.eventmanager.storage.mongo;

import java.time.Instant;
import java.util.Date;

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

import com.bloom.eventmanager.storage.oauth.OAuthStateStore;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The OAuth state nonce against a real MongoDB. OAuthStateStoreTest mocks
 * MongoTemplate, so a record that did not map back out of {@code findAndRemove}
 * would pass there and make every real "Connect Google Drive" end in
 * {@code ?auth=error}.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "spring.config.import=",
        "eureka.client.enabled=false",
        "spring.cloud.discovery.enabled=false",
        "bloom.security.internal-token=test-token",
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks",
        "spring.kafka.listener.auto-startup=false",
        // Off in production too; OAuthStateIndexInitializer owns the TTL index.
        "spring.data.mongodb.auto-index-creation=false"
})
class OAuthStateStoreIntegrationTests {

    // Same pin as deploy/docker-compose.yml and MongoTokenStoreIntegrationTests.
    @Container
    @ServiceConnection
    static MongoDBContainer mongo = new MongoDBContainer("mongo:8.2");

    @Autowired
    private OAuthStateStore store;

    @Autowired
    private MongoTemplate template;

    /** Deletes the documents but not the collection, so the index created at startup survives. */
    @BeforeEach
    void clean() {
        template.getCollection("oauthstates").deleteMany(new Document());
    }

    @Test
    void aMintedStateRedeemsExactlyOnce() {
        String state = store.mint();

        assertThat(store.consume(state)).isTrue();
        assertThat(store.consume(state)).isFalse();
        assertThat(template.getCollection("oauthstates").countDocuments()).isZero();
    }

    /** A TTL index only ever expires BSON dates; a string or a long would sit there forever. */
    @Test
    void storesTheNonceAsTheIdWithABsonDateExpiry() {
        String state = store.mint();

        Document raw = template.getCollection("oauthstates").find().first();
        assertThat(raw).isNotNull();
        assertThat(raw.getString("_id")).isEqualTo(state);
        assertThat(raw.get("expiresAt")).isInstanceOf(Date.class);
        assertThat(raw.getDate("expiresAt")).isAfter(new Date());
    }

    @Test
    void anExpiredStateIsRejectedAndRemoved() {
        template.save(new OAuthStateDocument("expired", Instant.now().minusSeconds(60)));

        assertThat(store.consume("expired")).isFalse();
        assertThat(template.getCollection("oauthstates").countDocuments()).isZero();
    }

    @Test
    void aStateThatWasNeverMintedIsRejected() {
        store.mint();

        assertThat(store.consume("never-minted")).isFalse();
        assertThat(template.getCollection("oauthstates").countDocuments()).isEqualTo(1);
    }

    /** Created at startup by OAuthStateIndexInitializer, not re-run here. */
    @Test
    void theTtlIndexOnExpiresAtExistsAfterStartup() {
        assertThat(template.indexOps("oauthstates").getIndexInfo())
                .anyMatch(index -> index.getExpireAfter().isPresent()
                        && index.getExpireAfter().get().isZero()
                        && index.getIndexFields().size() == 1
                        && "expiresAt".equals(index.getIndexFields().get(0).getKey()));
    }
}
