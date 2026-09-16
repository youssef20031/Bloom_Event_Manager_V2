package com.bloom.eventmanager.storage.mongo;

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

import com.bloom.eventmanager.storage.oauth.StoredToken;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "spring.config.import=",
        "eureka.client.enabled=false",
        "spring.cloud.discovery.enabled=false",
        "spring.kafka.listener.auto-startup=false",
        "spring.data.mongodb.auto-index-creation=true"
})
class MongoTokenStoreIntegrationTests {

    // 8.2, not 8.0: MongoDB 8.0 refuses to start on Linux kernel 6.19+
    // (SERVER-121912), which Docker Desktop's VM kernel is well past.
    // Same pin as deploy/docker-compose.yml.
    @Container
    @ServiceConnection
    static MongoDBContainer mongo = new MongoDBContainer("mongo:8.2");

    @Autowired
    private MongoTokenStore store;

    @Autowired
    private MongoTemplate template;

    @BeforeEach
    void clean() {
        template.getCollection("drivetokens").drop();
    }

    @Test
    void roundTripsATokenInV1sDocumentShape() {
        store.save("default", new StoredToken("access", "refresh", "https://www.googleapis.com/auth/drive.file", "Bearer", 1_800_000_000_000L));

        assertThat(store.load("default")).contains(
                new StoredToken("access", "refresh", "https://www.googleapis.com/auth/drive.file", "Bearer", 1_800_000_000_000L));

        Document raw = template.getCollection("drivetokens").find().first();
        assertThat(raw).isNotNull();
        assertThat(raw.getString("accountId")).isEqualTo("default");
        Document token = raw.get("token", Document.class);
        assertThat(token.getString("access_token")).isEqualTo("access");
        assertThat(token.getString("refresh_token")).isEqualTo("refresh");
        assertThat(token.getLong("expiry_date")).isEqualTo(1_800_000_000_000L);
    }

    @Test
    void savingAgainUpdatesRatherThanDuplicates() {
        store.save("default", new StoredToken("a1", "r1", null, "Bearer", 1L));
        store.save("default", new StoredToken("a2", "r2", null, "Bearer", 2L));

        assertThat(template.getCollection("drivetokens").countDocuments()).isEqualTo(1);
        assertThat(store.load("default").orElseThrow().accessToken()).isEqualTo("a2");
    }

    @Test
    void loadsATokenWrittenByV1() {
        template.getCollection("drivetokens").insertOne(new Document("accountId", "default")
                .append("token", new Document("access_token", "v1-access").append("refresh_token", "v1-refresh")
                        .append("scope", "s").append("token_type", "Bearer").append("expiry_date", 42L)));

        assertThat(store.load("default")).contains(new StoredToken("v1-access", "v1-refresh", "s", "Bearer", 42L));
    }

    @Test
    void clearRemovesTheToken() {
        store.save("default", new StoredToken("a", "r", null, "Bearer", 1L));

        store.clear("default");

        assertThat(store.load("default")).isEmpty();
    }
}
