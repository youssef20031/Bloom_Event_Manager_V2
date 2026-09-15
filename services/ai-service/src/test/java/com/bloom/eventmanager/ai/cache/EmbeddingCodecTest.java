package com.bloom.eventmanager.ai.cache;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EmbeddingCodecTest {

    @Test
    void encodesAsLittleEndianFloat32Base64ExactlyLikeV1() {
        // Node: Buffer.from(new Float32Array([1, 0.5]).buffer).toString("base64") === "AACAPwAAAD8="
        assertThat(EmbeddingCodec.encode(new float[] { 1f, 0.5f })).isEqualTo("AACAPwAAAD8=");
    }

    @Test
    void roundTripsArbitraryVectors() {
        float[] original = { 0.123456f, -0.98765f, 3.4e-5f, 0f, 1e10f };

        float[] back = EmbeddingCodec.decode(EmbeddingCodec.encode(original));

        assertThat(back).containsExactly(original);
    }

    @Test
    void decodesAnEmptyPayloadToAnEmptyVector() {
        assertThat(EmbeddingCodec.decode("")).isEmpty();
    }

    @Test
    void hashesTextToTheFirst32HexCharsOfSha256LikeV1() {
        // sha256("hello") = 2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824
        assertThat(EmbeddingCodec.hashText("hello")).isEqualTo("2cf24dba5fb0a30e26e83b2ac5b9e29e");
    }

    @Test
    void hashIsStableForUnicode() {
        assertThat(EmbeddingCodec.hashText("فعاليات بناء الفريق"))
                .isEqualTo(EmbeddingCodec.hashText("فعاليات بناء الفريق"))
                .hasSize(32);
    }
}
