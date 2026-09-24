package com.bloom.eventmanager.storage.mongo;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A single-use CSRF nonce for the Google OAuth flow.
 *
 * <p>Created by {@link OAuthStateIndexInitializer}, not by the {@link Indexed}
 * annotation below: auto-index-creation is off so that an unreachable MongoDB
 * cannot abort startup (see {@link DriveTokenDocument}). The annotation
 * documents the intent and still applies if a deployment turns
 * auto-index-creation back on.
 */
@Document(collection = "oauthstates")
public record OAuthStateDocument(@Id String state, @Indexed(expireAfterSeconds = 0) Instant expiresAt) {
}
