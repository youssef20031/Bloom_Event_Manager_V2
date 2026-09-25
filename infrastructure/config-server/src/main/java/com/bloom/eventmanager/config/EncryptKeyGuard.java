package com.bloom.eventmanager.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Refuses to start the Config Server without a real {@code encrypt.key}.
 *
 * <p>application.yml sets {@code encrypt.key: ${ENCRYPT_KEY}}, but property binding
 * leaves an unresolvable placeholder in place as literal text, so with ENCRYPT_KEY
 * unset the server used to start normally keyed with the string
 * {@code ${ENCRYPT_KEY}}: it served every {@code {cipher}} value as
 * {@code invalid.<name>}, and {@code POST /encrypt} returned ciphertext that anyone
 * who has read this repository can decrypt. Compose's {@code ${ENCRYPT_KEY:?}} guard
 * covers only Docker; this covers a host run too.
 *
 * <p>{@link Environment#resolveRequiredPlaceholders} resolves strictly, nested
 * placeholders included, so a missing ENCRYPT_KEY throws here instead of passing
 * through as text. A blank one is rejected explicitly.
 */
@Configuration(proxyBeanMethods = false)
class EncryptKeyGuard {

    static final String MISSING_KEY = "ENCRYPT_KEY is not set, or is blank. The Config Server will not start "
            + "without it: it could not decrypt the {cipher} values in config-repo, and POST /encrypt would "
            + "encrypt under a key anyone can guess. Set ENCRYPT_KEY in deploy/.env for Compose, or in the "
            + "run configuration's environment for a host run -- the same key the committed ciphertext was "
            + "encrypted with.";

    EncryptKeyGuard(Environment environment) {
        String key;
        try {
            key = environment.resolveRequiredPlaceholders("${encrypt.key}");
        }
        catch (IllegalArgumentException unresolved) {
            throw new IllegalStateException(MISSING_KEY, unresolved);
        }
        if (key.isBlank()) {
            throw new IllegalStateException(MISSING_KEY);
        }
    }
}
