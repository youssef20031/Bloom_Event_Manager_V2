package com.bloom.eventmanager.storage.mongo;

import java.time.Instant;
import java.util.Map;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/** V1's Mongoose {@code DriveToken} model: collection "drivetokens", token stored verbatim. */
@Document(collection = "drivetokens")
public class DriveTokenDocument {

    @Id
    private String id;

    /**
     * Created by {@link DriveTokenIndexInitializer}, not by this annotation:
     * auto-index-creation is off so that an unreachable MongoDB cannot abort
     * startup. The annotation documents the intent and still applies if a
     * deployment turns auto-index-creation back on.
     */
    @Indexed(unique = true)
    private String accountId;

    private Map<String, Object> token;

    private Instant createdAt;

    private Instant updatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = accountId; }
    public Map<String, Object> getToken() { return token; }
    public void setToken(Map<String, Object> token) { this.token = token; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
