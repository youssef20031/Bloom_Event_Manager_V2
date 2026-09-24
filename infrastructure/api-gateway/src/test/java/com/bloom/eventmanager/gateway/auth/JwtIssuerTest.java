package com.bloom.eventmanager.gateway.auth;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtIssuerTest {

    private final AuthProperties properties = new AuthProperties("bloom", "correct horse battery staple",
            "", "", Duration.ofHours(12), "internal-secret");
    private final JwtKeys keys = JwtKeys.from(properties);
    private final JwtIssuer issuer = new JwtIssuer(keys, properties);

    @Test
    void issuesAnRs256TokenValidatableWithThePublicKey() {
        String token = issuer.issue("bloom");

        Jwt decoded = NimbusJwtDecoder.withPublicKey(keys.publicKey()).build().decode(token);
        assertThat(decoded.getSubject()).isEqualTo("bloom");
        assertThat(decoded.getIssuer()).hasToString("https://bloom-gateway");
        assertThat(decoded.getHeaders()).containsEntry("alg", "RS256").containsEntry("kid", keys.keyId());
        assertThat(decoded.getExpiresAt()).isAfter(Instant.now().plus(Duration.ofHours(11)));
    }

    @Test
    void checksCredentialsInConstantTimeAndRejectsWrongOnes() {
        assertThat(issuer.credentialsMatch("bloom", "correct horse battery staple")).isTrue();
        assertThat(issuer.credentialsMatch("bloom", "wrong")).isFalse();
        assertThat(issuer.credentialsMatch("someone", "correct horse battery staple")).isFalse();
        assertThat(issuer.credentialsMatch(null, null)).isFalse();
    }

    @Test
    void generatesAnEphemeralKeyPairWhenNoPemIsConfigured() {
        assertThat(keys.ephemeral()).isTrue();
        assertThat(keys.publicKey().getModulus().bitLength()).isEqualTo(2048);
    }

    @Test
    void loadsAConfiguredPemPair() throws Exception {
        java.security.KeyPairGenerator generator = java.security.KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        java.security.KeyPair pair = generator.generateKeyPair();
        String privatePem = pem("PRIVATE KEY", pair.getPrivate().getEncoded());
        String publicPem = pem("PUBLIC KEY", pair.getPublic().getEncoded());

        JwtKeys loaded = JwtKeys.from(new AuthProperties("u", "p", privatePem, publicPem, Duration.ofHours(1), "t"));

        assertThat(loaded.ephemeral()).isFalse();
        assertThat(loaded.publicKey().getModulus()).isEqualTo(((java.security.interfaces.RSAPublicKey) pair.getPublic()).getModulus());
    }

    @Test
    void refusesHalfAKeyPair() {
        assertThatThrownBy(() -> JwtKeys.from(new AuthProperties("u", "p", "-----BEGIN PRIVATE KEY-----\nAA==\n-----END PRIVATE KEY-----", "", Duration.ofHours(1), "t")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("both");
    }

    private static String pem(String type, byte[] der) {
        return "-----BEGIN " + type + "-----\n" + java.util.Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der)
                + "\n-----END " + type + "-----\n";
    }
}
