package com.bloom.eventmanager.storage.oauth;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

import com.bloom.eventmanager.storage.mongo.OAuthStateDocument;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OAuthStateStoreTest {

    private final MongoTemplate mongoTemplate = mock(MongoTemplate.class);
    private final OAuthStateStore store = new OAuthStateStore(mongoTemplate, Duration.ofMinutes(10));

    @Test
    void mintsAnUnguessableNonceAndPersistsIt() {
        String state = store.mint();

        assertThat(state).isNotBlank();
        // 256 bits base64url without padding.
        assertThat(state.length()).isGreaterThanOrEqualTo(43);
        verify(mongoTemplate).save(any(OAuthStateDocument.class));
    }

    @Test
    void mintsADifferentNonceEveryTime() {
        assertThat(store.mint()).isNotEqualTo(store.mint());
    }

    @Test
    void consumesAValidNonceExactlyOnce() {
        OAuthStateDocument doc = new OAuthStateDocument("abc", Instant.now().plusSeconds(60));
        when(mongoTemplate.findAndRemove(any(Query.class), eq(OAuthStateDocument.class)))
                .thenReturn(doc, (OAuthStateDocument) null);

        assertThat(store.consume("abc")).isTrue();
        assertThat(store.consume("abc")).isFalse();
    }

    @Test
    void rejectsAnUnknownNonce() {
        when(mongoTemplate.findAndRemove(any(Query.class), eq(OAuthStateDocument.class))).thenReturn(null);

        assertThat(store.consume("never-issued")).isFalse();
    }

    @Test
    void rejectsAnExpiredNonce() {
        OAuthStateDocument expired = new OAuthStateDocument("old", Instant.now().minusSeconds(1));
        when(mongoTemplate.findAndRemove(any(Query.class), eq(OAuthStateDocument.class))).thenReturn(expired);

        assertThat(store.consume("old")).isFalse();
    }

    @Test
    void rejectsNullAndBlank() {
        assertThat(store.consume(null)).isFalse();
        assertThat(store.consume("  ")).isFalse();
    }
}
