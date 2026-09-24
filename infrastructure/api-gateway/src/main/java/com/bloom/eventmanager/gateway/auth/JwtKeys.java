package com.bloom.eventmanager.gateway.auth;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.converter.RsaKeyConverters;

/** The gateway's signing key pair: configured PEMs in production, an ephemeral pair in development. */
public record JwtKeys(RSAPublicKey publicKey, RSAPrivateKey privateKey, RSAKey jwk, boolean ephemeral) {

    private static final Logger log = LoggerFactory.getLogger(JwtKeys.class);

    public static JwtKeys from(AuthProperties properties) {
        boolean hasPrivate = !properties.privateKeyPem().isBlank();
        boolean hasPublic = !properties.publicKeyPem().isBlank();
        if (hasPrivate != hasPublic) {
            throw new IllegalStateException("bloom.auth.private-key-pem and public-key-pem must both be set, or neither");
        }
        if (hasPrivate) {
            RSAPrivateKey privateKey = RsaKeyConverters.pkcs8()
                    .convert(new ByteArrayInputStream(properties.privateKeyPem().getBytes(StandardCharsets.UTF_8)));
            RSAPublicKey publicKey = RsaKeyConverters.x509()
                    .convert(new ByteArrayInputStream(properties.publicKeyPem().getBytes(StandardCharsets.UTF_8)));
            return new JwtKeys(publicKey, privateKey, jwk(publicKey, privateKey), false);
        }
        log.warn("No JWT key pair configured — generating an EPHEMERAL RSA key. Every token dies with this process. Dev only.");
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair pair = generator.generateKeyPair();
            RSAPublicKey publicKey = (RSAPublicKey) pair.getPublic();
            RSAPrivateKey privateKey = (RSAPrivateKey) pair.getPrivate();
            return new JwtKeys(publicKey, privateKey, jwk(publicKey, privateKey), true);
        }
        catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("RSA is mandatory in every JVM", ex);
        }
    }

    public String keyId() {
        return jwk.getKeyID();
    }

    private static RSAKey jwk(RSAPublicKey publicKey, RSAPrivateKey privateKey) {
        try {
            RSAKey unnamed = new RSAKey.Builder(publicKey).privateKey(privateKey).keyUse(KeyUse.SIGNATURE).build();
            return new RSAKey.Builder(unnamed).keyID(unnamed.computeThumbprint().toString()).build();
        }
        catch (JOSEException ex) {
            throw new IllegalStateException("Cannot compute JWK thumbprint", ex);
        }
    }
}
