package com.bloom.eventmanager.storage.oauth;

/** googleapis' token payload, field for field, so V1's stored tokens load unchanged. */
public record StoredToken(String accessToken, String refreshToken, String scope, String tokenType, Long expiryDate) {
}
