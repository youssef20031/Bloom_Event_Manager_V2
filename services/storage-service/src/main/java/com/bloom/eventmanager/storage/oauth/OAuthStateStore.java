package com.bloom.eventmanager.storage.oauth;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import com.bloom.eventmanager.storage.mongo.OAuthStateDocument;

/**
 * Single-use nonces for the Google OAuth {@code state} parameter.
 *
 * <p>Without this, the callback accepts an authorization code from anyone. The
 * OAuth client id and the registered redirect URI are both public, so an
 * attacker can run the consent flow against their own Google account, capture
 * the code, and hand it to our callback — binding their Drive as the fleet's
 * single Drive identity and poisoning the corpus the Event Advisor answers from.
 *
 * <p>Not a {@code @Component}: its constructor takes a {@link Duration}, which
 * Spring cannot autowire on its own. It is registered as a bean by
 * {@code OAuthStateConfig}, which resolves the TTL from
 * {@code StorageProperties.stateTtlMs()}.
 */
public class OAuthStateStore {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final MongoTemplate mongoTemplate;
    private final Duration ttl;

    public OAuthStateStore(MongoTemplate mongoTemplate, Duration ttl) {
        this.mongoTemplate = mongoTemplate;
        this.ttl = ttl;
    }

    public String mint() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String state = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        mongoTemplate.save(new OAuthStateDocument(state, Instant.now().plus(ttl)));
        return state;
    }

    /**
     * True at most once per minted nonce. findAndRemove is Mongo's findAndModify
     * with remove:true -- one server-side operation, so two callbacks racing on
     * the same nonce cannot both be told yes.
     */
    public boolean consume(String state) {
        if (state == null || state.isBlank()) {
            return false;
        }
        OAuthStateDocument found = mongoTemplate.findAndRemove(
                Query.query(Criteria.where("_id").is(state)), OAuthStateDocument.class);
        return found != null && found.expiresAt().isAfter(Instant.now());
    }
}
