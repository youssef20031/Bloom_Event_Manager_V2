package com.bloom.eventmanager.gateway.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Component;

@Component
public class JwtIssuer {

    // Must be an absolute URI: Jwt#getIssuer() is typed as java.net.URL and
    // Spring Security's ClaimConversionService throws IllegalArgumentException
    // for any "iss" claim that new URI(value).toURL() cannot parse (true since
    // Spring Security 5.0, unrelated to this project's Boot/Security version).
    static final String ISSUER = "https://bloom-gateway";

    private final JwtKeys keys;
    private final AuthProperties properties;
    private final NimbusJwtEncoder encoder;

    public JwtIssuer(JwtKeys keys, AuthProperties properties) {
        this.keys = keys;
        this.properties = properties;
        this.encoder = new NimbusJwtEncoder(new ImmutableJWKSet<SecurityContext>(new JWKSet(keys.jwk())));
    }

    public String issue(String subject) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .subject(subject)
                .issuedAt(now)
                .expiresAt(now.plus(properties.tokenTtl()))
                .claim("scope", "user")
                .build();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(keys.keyId()).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    /** V1's safeEqual: compare fixed-length digests so neither content nor length leaks through timing. */
    public boolean credentialsMatch(String username, String password) {
        if (username == null || password == null) {
            return false;
        }
        boolean userOk = MessageDigest.isEqual(sha256(username), sha256(properties.user()));
        boolean passOk = MessageDigest.isEqual(sha256(password), sha256(properties.password()));
        return userOk & passOk;
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        }
        catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
