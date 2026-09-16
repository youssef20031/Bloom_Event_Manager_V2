package com.bloom.eventmanager.ingestion.domain;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.bloom.eventmanager.ingestion.config.IngestionProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TextChunkerTest {

    private static TextChunker chunker(int size, int overlap) {
        return new TextChunker(new IngestionProperties("./ISM_PDF", size, overlap, 100, 300f, "eng", "", 20));
    }

    @Test
    void slidesAWindowWithOverlapExactlyLikeV1() {
        List<TextChunk> chunks = chunker(4, 1).chunk("abcdefghij", "doc.pdf");

        assertThat(chunks).extracting(TextChunk::text).containsExactly("abcd", "defg", "ghij", "j");
        assertThat(chunks).extracting(TextChunk::id)
                .containsExactly("doc.pdf-0-0", "doc.pdf-0-1", "doc.pdf-0-2", "doc.pdf-0-3");
        assertThat(chunks).allSatisfy(c -> {
            assertThat(c.source()).isEqualTo("doc.pdf");
            assertThat(c.page()).isZero();
        });
    }

    @Test
    void emptyTextYieldsNoChunks() {
        assertThat(chunker(1000, 200).chunk("", "doc.pdf")).isEmpty();
    }

    @Test
    void textShorterThanOneChunkIsOneChunk() {
        assertThat(chunker(1000, 200).chunk("short", "doc.pdf")).singleElement()
                .satisfies(c -> assertThat(c.text()).isEqualTo("short"));
    }

    @Test
    void refusesAnOverlapThatWouldNeverAdvance() {
        assertThatThrownBy(() -> chunker(100, 100)).isInstanceOf(IllegalArgumentException.class);
    }
}
