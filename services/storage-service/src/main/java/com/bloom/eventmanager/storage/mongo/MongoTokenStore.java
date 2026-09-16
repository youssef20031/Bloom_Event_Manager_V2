package com.bloom.eventmanager.storage.mongo;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.bloom.eventmanager.storage.oauth.StoredToken;
import com.bloom.eventmanager.storage.oauth.TokenStore;

/**
 * Load swallows failures (startup must not depend on Mongo being up); save
 * does not (a silent save failure would look like success and strand the
 * user unauthenticated on the next restart). Same rule V1 applied.
 */
@Component
public class MongoTokenStore implements TokenStore {

    private static final Logger log = LoggerFactory.getLogger(MongoTokenStore.class);

    private final DriveTokenRepository repository;

    public MongoTokenStore(DriveTokenRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<StoredToken> load(String accountId) {
        try {
            return repository.findByAccountId(accountId).map(doc -> fromMap(doc.getToken()));
        }
        catch (RuntimeException ex) {
            log.warn("Could not load Drive token from MongoDB — treating as unauthenticated: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void save(String accountId, StoredToken token) {
        DriveTokenDocument doc = repository.findByAccountId(accountId).orElseGet(() -> {
            DriveTokenDocument fresh = new DriveTokenDocument();
            fresh.setAccountId(accountId);
            fresh.setCreatedAt(Instant.now());
            return fresh;
        });
        doc.setToken(toMap(token));
        doc.setUpdatedAt(Instant.now());
        repository.save(doc);
    }

    @Override
    public void clear(String accountId) {
        try {
            repository.deleteByAccountId(accountId);
        }
        catch (RuntimeException ex) {
            log.warn("Could not clear Drive token from MongoDB: {}", ex.getMessage());
        }
    }

    static Map<String, Object> toMap(StoredToken token) {
        Map<String, Object> map = new HashMap<>();
        map.put("access_token", token.accessToken());
        map.put("refresh_token", token.refreshToken());
        map.put("scope", token.scope());
        map.put("token_type", token.tokenType());
        map.put("expiry_date", token.expiryDate());
        return map;
    }

    static StoredToken fromMap(Map<String, Object> map) {
        Object expiry = map.get("expiry_date");
        return new StoredToken(
                (String) map.get("access_token"),
                (String) map.get("refresh_token"),
                (String) map.get("scope"),
                (String) map.get("token_type"),
                expiry instanceof Number n ? n.longValue() : null);
    }
}
