package com.bloom.eventmanager.ingestion.local;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import com.bloom.eventmanager.ingestion.config.IngestionProperties;
import com.bloom.eventmanager.ingestion.domain.DocumentIngestionPipeline;
import com.bloom.eventmanager.ingestion.domain.IngestOutcome;

/**
 * V1's {@code npm run ingest} / {@code POST /ingest}: every PDF in the
 * configured directory, no "File Name:" header (V1's local path never added
 * one), each file independent so one bad PDF cannot sink the batch.
 */
@Service
public class LocalDirectoryIngestService {

    private static final Logger log = LoggerFactory.getLogger(LocalDirectoryIngestService.class);

    private final DocumentIngestionPipeline pipeline;
    private final IngestJobRegistry registry;
    private final Path directory;

    public LocalDirectoryIngestService(DocumentIngestionPipeline pipeline, IngestJobRegistry registry,
            IngestionProperties properties) {
        this.pipeline = pipeline;
        this.registry = registry;
        this.directory = Path.of(properties.pdfDirectory());
    }

    public IngestJob start(String onlySource) {
        IngestJob job = IngestJob.running(UUID.randomUUID().toString());
        registry.put(job);
        return job;
    }

    @Async
    public void run(String jobId, String onlySource) {
        IngestJob job = registry.get(jobId).orElseThrow();
        List<IngestOutcome> results = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        List<Path> pdfs;
        try (Stream<Path> entries = Files.list(directory)) {
            pdfs = entries.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".pdf"))
                    .filter(p -> onlySource == null || p.getFileName().toString().equals(onlySource))
                    .sorted()
                    .toList();
        }
        catch (IOException ex) {
            registry.put(job.failed("Cannot read " + directory + ": " + ex.getMessage()));
            return;
        }

        for (Path pdf : pdfs) {
            String source = pdf.getFileName().toString();
            try {
                results.add(pipeline.ingest(Files.readAllBytes(pdf), source, null, false));
            }
            catch (IOException | RuntimeException ex) {
                log.error("Failed to ingest {}", source, ex);
                errors.add(source + ": " + ex.getMessage());
            }
        }
        registry.put(job.completed(results, errors));
        log.info("Local ingest {} complete: {} files, {} errors", jobId, results.size(), errors.size());
    }
}
