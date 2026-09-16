package com.bloom.eventmanager.ingestion.domain;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.bloom.eventmanager.ingestion.config.IngestionProperties;

/**
 * V1's {@code lib/chunkText.ts}: a fixed window sliding by (size - overlap),
 * ids {@code <source>-<page>-<index>} with page always 0. Deterministic ids
 * are what make re-ingestion and Kafka redelivery idempotent downstream.
 */
@Component
public class TextChunker {

    private final int chunkSize;
    private final int overlap;

    public TextChunker(IngestionProperties properties) {
        if (properties.chunkOverlap() >= properties.chunkSize()) {
            throw new IllegalArgumentException("chunkOverlap must be smaller than chunkSize");
        }
        this.chunkSize = properties.chunkSize();
        this.overlap = properties.chunkOverlap();
    }

    public List<TextChunk> chunk(String text, String source) {
        List<TextChunk> chunks = new ArrayList<>();
        int start = 0;
        int index = 0;
        while (start < text.length()) {
            int end = Math.min(start + chunkSize, text.length());
            chunks.add(new TextChunk(source + "-0-" + index, text.substring(start, end), source, 0));
            start += chunkSize - overlap;
            index++;
        }
        return chunks;
    }
}
