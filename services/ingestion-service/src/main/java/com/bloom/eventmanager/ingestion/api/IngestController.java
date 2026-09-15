package com.bloom.eventmanager.ingestion.api;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.bloom.eventmanager.common.error.NotFoundException;
import com.bloom.eventmanager.ingestion.api.dto.IngestAccepted;
import com.bloom.eventmanager.ingestion.api.dto.IngestRequest;
import com.bloom.eventmanager.ingestion.local.IngestJob;
import com.bloom.eventmanager.ingestion.local.IngestJobRegistry;
import com.bloom.eventmanager.ingestion.local.LocalDirectoryIngestService;

@RestController
@RequestMapping("/api/v1/ingest")
public class IngestController {

    private final LocalDirectoryIngestService ingestService;
    private final IngestJobRegistry registry;

    public IngestController(LocalDirectoryIngestService ingestService, IngestJobRegistry registry) {
        this.ingestService = ingestService;
        this.registry = registry;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public IngestAccepted start(@RequestBody(required = false) IngestRequest request) {
        String onlySource = request == null ? null : request.source();
        IngestJob job = ingestService.start(onlySource);
        ingestService.run(job.jobId(), onlySource);
        return new IngestAccepted(job.jobId(), job.status().name());
    }

    @GetMapping("/{jobId}")
    public IngestJob status(@PathVariable String jobId) {
        return registry.get(jobId).orElseThrow(() -> new NotFoundException("No ingest job " + jobId));
    }
}
