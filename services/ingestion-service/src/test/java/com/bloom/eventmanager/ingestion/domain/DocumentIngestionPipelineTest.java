package com.bloom.eventmanager.ingestion.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import com.bloom.eventmanager.events.ChunkBatchReady;
import com.bloom.eventmanager.ingestion.client.AiClient;
import com.bloom.eventmanager.ingestion.client.KnowledgeClient;
import com.bloom.eventmanager.ingestion.client.dto.DeleteDocumentResponse;
import com.bloom.eventmanager.ingestion.client.dto.EmbedBatchRequest;
import com.bloom.eventmanager.ingestion.client.dto.EmbedBatchResponse;
import com.bloom.eventmanager.ingestion.config.IngestionProperties;
import com.bloom.eventmanager.ingestion.kafka.ChunkBatchPublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentIngestionPipelineTest {

    private static final byte[] PDF = { 1, 2, 3 };

    private final TextExtractor extractor = mock(TextExtractor.class);
    private final OcrEngine ocr = mock(OcrEngine.class);
    private final AiClient ai = mock(AiClient.class);
    private final KnowledgeClient knowledge = mock(KnowledgeClient.class);
    private final ChunkBatchPublisher publisher = mock(ChunkBatchPublisher.class);
    // chunkSize 10 / overlap 2 keeps fixtures tiny; batch size 5 for embedding.
    private final IngestionProperties properties = new IngestionProperties("./ISM_PDF", 10, 2, 100, 300f, "eng", "", 5);
    private final DocumentIngestionPipeline pipeline = new DocumentIngestionPipeline(
            extractor, ocr, new TextChunker(properties), ai, knowledge, publisher, properties);

    @Test
    void parsesChunksEmbedsDeletesOldChunksThenPublishesOrderedBatches() {
        when(extractor.extract(PDF)).thenReturn("x".repeat(400)); // 400 chars, window 10, step 8 -> 50 chunks
        when(ai.embedBatch(any())).thenAnswer(inv -> embeddingsFor(inv.getArgument(0)));
        when(knowledge.deleteDocument("doc.pdf")).thenReturn(new DeleteDocumentResponse(3));

        IngestOutcome outcome = pipeline.ingest(PDF, "doc.pdf", "drive-1", false);

        assertThat(outcome.chunkCount()).isEqualTo(50);
        assertThat(outcome.ocrUsed()).isFalse();
        verify(ocr, never()).recognize(any());

        InOrder order = inOrder(knowledge, publisher);
        order.verify(knowledge).deleteDocument("doc.pdf");
        ArgumentCaptor<ChunkBatchReady> batches = ArgumentCaptor.forClass(ChunkBatchReady.class);
        order.verify(publisher, times(3)).publish(batches.capture());

        List<ChunkBatchReady> published = batches.getAllValues();
        assertThat(published).extracting(ChunkBatchReady::seq).containsExactly(0, 1, 2);
        assertThat(published).extracting(ChunkBatchReady::last).containsExactly(false, false, true);
        assertThat(published).extracting(b -> b.chunks().size()).containsExactly(20, 20, 10);
        assertThat(published.get(0).driveFileId()).isEqualTo("drive-1");
        assertThat(published.get(0).chunks().get(0).id()).isEqualTo("doc.pdf-0-0");
        assertThat(published.get(2).chunks().get(9).id()).isEqualTo("doc.pdf-0-49");
        assertThat(published.get(0).chunks().get(0).embedding()).containsExactly(0f);
    }

    @Test
    void embedsInSlicesOfTheConfiguredBatchSize() {
        when(extractor.extract(PDF)).thenReturn("x".repeat(96)); // 12 chunks -> 3 slices of 5,5,2
        // 96 chars is under ocrMinChars (100), so the OCR fallback runs. It finds
        // nothing, the text layer is kept, and the chunk count is unaffected —
        // but the stub is required, or the engine returns null and the pipeline
        // NPEs on a value its own contract says is never null.
        when(ocr.recognize(PDF)).thenReturn("");
        when(ai.embedBatch(any())).thenAnswer(inv -> embeddingsFor(inv.getArgument(0)));

        pipeline.ingest(PDF, "doc.pdf", null, false);

        ArgumentCaptor<EmbedBatchRequest> requests = ArgumentCaptor.forClass(EmbedBatchRequest.class);
        verify(ai, times(3)).embedBatch(requests.capture());
        assertThat(requests.getAllValues()).extracting(r -> r.texts().size()).containsExactly(5, 5, 2);
    }

    @Test
    void fallsBackToOcrWhenTheTextLayerIsTooShort() {
        when(extractor.extract(PDF)).thenReturn("   ");
        when(ocr.recognize(PDF)).thenReturn("scanned words here");
        when(ai.embedBatch(any())).thenAnswer(inv -> embeddingsFor(inv.getArgument(0)));

        IngestOutcome outcome = pipeline.ingest(PDF, "scan.pdf", null, false);

        assertThat(outcome.ocrUsed()).isTrue();
        assertThat(outcome.chunkCount()).isGreaterThan(0);
    }

    @Test
    void keepsTheShortTextLayerWhenOcrFindsNothing() {
        when(extractor.extract(PDF)).thenReturn("tiny");
        when(ocr.recognize(PDF)).thenReturn("");
        when(ai.embedBatch(any())).thenAnswer(inv -> embeddingsFor(inv.getArgument(0)));

        IngestOutcome outcome = pipeline.ingest(PDF, "tiny.pdf", null, false);

        assertThat(outcome.ocrUsed()).isFalse();
        assertThat(outcome.chunkCount()).isEqualTo(1);
    }

    @Test
    void prependsTheFileNameHeaderForDriveFilesOnly() {
        // V1's real chunk size, not the tiny fixture one: the header alone is 22
        // characters, so a 10-character window would cut it to "File Name:" and
        // the assertion could never see the source name.
        IngestionProperties wideChunks = new IngestionProperties("./ISM_PDF", 1000, 200, 100, 300f, "eng", "", 5);
        DocumentIngestionPipeline widePipeline = new DocumentIngestionPipeline(
                extractor, ocr, new TextChunker(wideChunks), ai, knowledge, publisher, wideChunks);
        when(extractor.extract(PDF)).thenReturn("body text that is long enough to skip the ocr fallback for good measure, yes indeed it is long enough now");
        when(ai.embedBatch(any())).thenAnswer(inv -> embeddingsFor(inv.getArgument(0)));

        widePipeline.ingest(PDF, "brief.pdf", "drive-1", true);

        ArgumentCaptor<ChunkBatchReady> batch = ArgumentCaptor.forClass(ChunkBatchReady.class);
        verify(publisher, org.mockito.Mockito.atLeastOnce()).publish(batch.capture());
        assertThat(batch.getAllValues().get(0).chunks().get(0).text()).startsWith("File Name: brief.pdf");
    }

    @Test
    void omitsTheFileNameHeaderForLocalFiles() {
        IngestionProperties wideChunks = new IngestionProperties("./ISM_PDF", 1000, 200, 100, 300f, "eng", "", 5);
        DocumentIngestionPipeline widePipeline = new DocumentIngestionPipeline(
                extractor, ocr, new TextChunker(wideChunks), ai, knowledge, publisher, wideChunks);
        when(extractor.extract(PDF)).thenReturn("body text that is long enough to skip the ocr fallback for good measure, yes indeed it is long enough now");
        when(ai.embedBatch(any())).thenAnswer(inv -> embeddingsFor(inv.getArgument(0)));

        widePipeline.ingest(PDF, "local.pdf", null, false);

        ArgumentCaptor<ChunkBatchReady> batch = ArgumentCaptor.forClass(ChunkBatchReady.class);
        verify(publisher, org.mockito.Mockito.atLeastOnce()).publish(batch.capture());
        assertThat(batch.getAllValues().get(0).chunks().get(0).text()).startsWith("body text");
    }

    @Test
    void anUnreadableDocumentPublishesOneEmptyLastBatchSoItStillCountsAsProcessed() {
        when(extractor.extract(PDF)).thenReturn("");
        when(ocr.recognize(PDF)).thenReturn("");

        IngestOutcome outcome = pipeline.ingest(PDF, "blank.pdf", "drive-2", true);

        assertThat(outcome.chunkCount()).isZero();
        verify(ai, never()).embedBatch(any());
        ArgumentCaptor<ChunkBatchReady> batch = ArgumentCaptor.forClass(ChunkBatchReady.class);
        verify(publisher).publish(batch.capture());
        assertThat(batch.getValue().chunks()).isEmpty();
        assertThat(batch.getValue().last()).isTrue();
        assertThat(batch.getValue().driveFileId()).isEqualTo("drive-2");
    }

    private static EmbedBatchResponse embeddingsFor(EmbedBatchRequest request) {
        List<float[]> vectors = new ArrayList<>();
        IntStream.range(0, request.texts().size()).forEach(i -> vectors.add(new float[] { i }));
        return new EmbedBatchResponse(vectors);
    }
}
