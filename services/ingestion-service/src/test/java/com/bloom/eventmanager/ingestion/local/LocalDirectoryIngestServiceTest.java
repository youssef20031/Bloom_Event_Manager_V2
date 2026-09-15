package com.bloom.eventmanager.ingestion.local;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.bloom.eventmanager.ingestion.config.IngestionProperties;
import com.bloom.eventmanager.ingestion.domain.DocumentIngestionPipeline;
import com.bloom.eventmanager.ingestion.domain.IngestOutcome;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LocalDirectoryIngestServiceTest {

    @TempDir
    Path dir;

    @Test
    void ingestsEveryPdfInTheDirectoryWithoutTheDriveHeader() throws IOException {
        Files.write(dir.resolve("a.pdf"), new byte[] { 1 });
        Files.write(dir.resolve("b.pdf"), new byte[] { 2 });
        Files.write(dir.resolve("notes.txt"), new byte[] { 3 });
        DocumentIngestionPipeline pipeline = mock(DocumentIngestionPipeline.class);
        when(pipeline.ingest(any(), any(), isNull(), eq(false)))
                .thenAnswer(inv -> new IngestOutcome(inv.getArgument(1), 4, false));
        IngestJobRegistry registry = new IngestJobRegistry();
        LocalDirectoryIngestService service = new LocalDirectoryIngestService(pipeline, registry,
                new IngestionProperties(dir.toString(), 1000, 200, 100, 300f, "eng", "", 20));

        IngestJob job = service.start(null);
        service.run(job.jobId(), null);

        IngestJob done = registry.get(job.jobId()).orElseThrow();
        assertThat(done.status()).isEqualTo(IngestJob.Status.COMPLETED);
        assertThat(done.results()).extracting(IngestOutcome::source).containsExactlyInAnyOrder("a.pdf", "b.pdf");
        verify(pipeline).ingest(new byte[] { 1 }, "a.pdf", null, false);
    }

    @Test
    void restrictsToOneSourceWhenAsked() throws IOException {
        Files.write(dir.resolve("a.pdf"), new byte[] { 1 });
        Files.write(dir.resolve("b.pdf"), new byte[] { 2 });
        DocumentIngestionPipeline pipeline = mock(DocumentIngestionPipeline.class);
        when(pipeline.ingest(any(), any(), isNull(), eq(false)))
                .thenAnswer(inv -> new IngestOutcome(inv.getArgument(1), 1, false));
        IngestJobRegistry registry = new IngestJobRegistry();
        LocalDirectoryIngestService service = new LocalDirectoryIngestService(pipeline, registry,
                new IngestionProperties(dir.toString(), 1000, 200, 100, 300f, "eng", "", 20));

        IngestJob job = service.start("b.pdf");
        service.run(job.jobId(), "b.pdf");

        assertThat(registry.get(job.jobId()).orElseThrow().results()).extracting(IngestOutcome::source)
                .containsExactly("b.pdf");
    }

    @Test
    void aFailingFileIsRecordedAndTheJobContinues() throws IOException {
        Files.write(dir.resolve("bad.pdf"), new byte[] { 1 });
        Files.write(dir.resolve("good.pdf"), new byte[] { 2 });
        DocumentIngestionPipeline pipeline = mock(DocumentIngestionPipeline.class);
        when(pipeline.ingest(eq(new byte[] { 1 }), eq("bad.pdf"), isNull(), eq(false)))
                .thenThrow(new RuntimeException("boom"));
        when(pipeline.ingest(eq(new byte[] { 2 }), eq("good.pdf"), isNull(), eq(false)))
                .thenReturn(new IngestOutcome("good.pdf", 2, false));
        IngestJobRegistry registry = new IngestJobRegistry();
        LocalDirectoryIngestService service = new LocalDirectoryIngestService(pipeline, registry,
                new IngestionProperties(dir.toString(), 1000, 200, 100, 300f, "eng", "", 20));

        IngestJob job = service.start(null);
        service.run(job.jobId(), null);

        IngestJob done = registry.get(job.jobId()).orElseThrow();
        assertThat(done.status()).isEqualTo(IngestJob.Status.COMPLETED);
        assertThat(done.errors()).singleElement().asString().contains("bad.pdf").contains("boom");
        assertThat(done.results()).extracting(IngestOutcome::source).containsExactly("good.pdf");
    }

    @Test
    void aMissingDirectoryFailsTheJob() {
        IngestJobRegistry registry = new IngestJobRegistry();
        LocalDirectoryIngestService service = new LocalDirectoryIngestService(mock(DocumentIngestionPipeline.class),
                registry, new IngestionProperties(dir.resolve("nope").toString(), 1000, 200, 100, 300f, "eng", "", 20));

        IngestJob job = service.start(null);
        service.run(job.jobId(), null);

        await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                assertThat(registry.get(job.jobId()).orElseThrow().status()).isEqualTo(IngestJob.Status.FAILED));
    }
}
