package com.bloom.eventmanager.ai.cache;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Byte-for-byte compatible with V1's CachedAIService encoding, so a V2
 * deployment inherits the warm Redis cache instead of re-paying Gemini.
 *
 * <p>V1: {@code Buffer.from(new Float32Array(v).buffer).toString("base64")} —
 * float32, little-endian (the only byte order Node ever ran on here).
 * Key hash: {@code sha256(text).hex.slice(0, 32)}.
 */
public final class EmbeddingCodec {

    private EmbeddingCodec() {
    }

    public static String encode(float[] embedding) {
        ByteBuffer buffer = ByteBuffer.allocate(embedding.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        buffer.asFloatBuffer().put(embedding);
        return Base64.getEncoder().encodeToString(buffer.array());
    }

    public static float[] decode(String encoded) {
        byte[] bytes = Base64.getDecoder().decode(encoded);
        FloatBuffer floats = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
        float[] out = new float[floats.remaining()];
        floats.get(out);
        return out;
    }

    public static String hashText(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 32);
        }
        catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is mandatory in every JVM", ex);
        }
    }
}
