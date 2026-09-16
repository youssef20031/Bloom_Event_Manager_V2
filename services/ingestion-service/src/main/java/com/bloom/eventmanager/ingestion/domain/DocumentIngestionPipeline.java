package com.bloom.eventmanager.ingestion.domain;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.bloom.eventmanager.common.error.ExternalServiceException;
import com.bloom.eventmanager.events.ChunkBatchReady;
import com.bloom.eventmanager.events.ChunkPayload;
import com.bloom.eventmanager.ingestion.client.AiClient;
import com.bloom.eventmanager.ingestion.client.KnowledgeClient;
import com.bloom.eventmanager.ingestion.client.dto.EmbedBatchRequest;
import com.bloom.eventmanager.ingestion.config.IngestionProperties;
import com.bloom.eventmanager.ingestion.kafka.ChunkBatchPublisher;

/**
 * V1's ingest steps in order: text layer → OCR fallback → (header) → chunk →
 * embed → clear the old document → publish batches of at most 20 chunks.
 *
 * <p>The old document is always deleted first (V1's local path did this).
 * Chunk ids are deterministic, so the MERGE downstream would overwrite
 * matching ids anyway — the delete exists to remove chunks a shrunken
 * document no longer has.
 */
@Service
public class DocumentIngestionPipeline {

    private static final Logger log = LoggerFactory.getLogger(DocumentIngestionPipeline.class);

    private final TextExtractor extractor;
    private final OcrEngine ocr;
    private final TextChunker chunker;
    private final AiClient ai;
    private final KnowledgeClient knowledge;
    private final ChunkBatchPublisher publisher;
    private final IngestionProperties properties;

    public DocumentIngestionPipeline(TextExtractor extractor, OcrEngine ocr, TextChunker chunker, AiClient ai,
            KnowledgeClient knowledge, ChunkBatchPublisher publisher, IngestionProperties properties) {
        this.extractor = extractor;
        this.ocr = ocr;
        this.chunker = chunker;
        this.ai = ai;
        this.knowledge = knowledge;
        this.publisher = publisher;
        this.properties = properties;
    }

    public IngestOutcome ingest(byte[] pdf, String source, String driveFileId, boolean includeFileNameHeader) {
        String text = extractor.extract(pdf);
        boolean ocrUsed = false;
        if (text.trim().length() < properties.ocrMinChars()) {
            log.info("{}: text layer has {} chars, running OCR", source, text.trim().length());
            String recognised = ocr.recognize(pdf);
            if (!recognised.isBlank()) {
                text = recognised;
                ocrUsed = true;
            }
        }

        List<TextChunk> chunks = List.of();
        if (!text.isBlank()) {
            String full = includeFileNameHeader ? "File Name: " + source + "\n\n" + text : text;
            chunks = chunker.chunk(full, source);
        }

        List<float[]> embeddings = embedAll(chunks);

        try {
            knowledge.deleteDocument(source);
        }
        catch (RuntimeException ex) {
            throw new ExternalServiceException("Could not clear previous chunks for " + source, ex);
        }

        publishBatches(source, driveFileId, chunks, embeddings);
        log.info("Ingested {}: {} chunks{}", source, chunks.size(), ocrUsed ? " (OCR)" : "");
        return new IngestOutcome(source, chunks.size(), ocrUsed);
    }

    private List<float[]> embedAll(List<TextChunk> chunks) {
        List<float[]> embeddings = new ArrayList<>(chunks.size());
        int batchSize = properties.embeddingBatchSize();
        for (int from = 0; from < chunks.size(); from += batchSize) {
            List<String> texts = chunks.subList(from, Math.min(from + batchSize, chunks.size()))
                    .stream().map(TextChunk::text).toList();
            try {
                embeddings.addAll(ai.embedBatch(new EmbedBatchRequest(texts)).embeddings());
            }
            catch (RuntimeException ex) {
                throw new ExternalServiceException("Embedding failed via ai-service", ex);
            }
        }
        return embeddings;
    }

    private void publishBatches(String source, String driveFileId, List<TextChunk> chunks, List<float[]> embeddings) {
        int max = ChunkBatchReady.MAX_CHUNKS_PER_BATCH;
        int batchCount = Math.max(1, (chunks.size() + max - 1) / max);
        for (int seq = 0; seq < batchCount; seq++) {
            int from = seq * max;
            int to = Math.min(from + max, chunks.size());
            List<ChunkPayload> payloads = new ArrayList<>();
            for (int i = from; i < to; i++) {
                TextChunk chunk = chunks.get(i);
                payloads.add(new ChunkPayload(chunk.id(), chunk.text(), chunk.source(), chunk.page(), embeddings.get(i)));
            }
            publisher.publish(new ChunkBatchReady(source, driveFileId, seq, seq == batchCount - 1, payloads));
        }
    }
}
