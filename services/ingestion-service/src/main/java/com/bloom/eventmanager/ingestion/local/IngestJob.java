package com.bloom.eventmanager.ingestion.local;

import java.time.Instant;
import java.util.List;

import com.bloom.eventmanager.ingestion.domain.IngestOutcome;

public record IngestJob(
        String jobId,
        Status status,
        Instant startedAt,
        Instant finishedAt,
        List<IngestOutcome> results,
        List<String> errors) {

    public enum Status { RUNNING, COMPLETED, FAILED }

    public static IngestJob running(String jobId) {
        return new IngestJob(jobId, Status.RUNNING, Instant.now(), null, List.of(), List.of());
    }

    public IngestJob completed(List<IngestOutcome> results, List<String> errors) {
        return new IngestJob(jobId, Status.COMPLETED, startedAt, Instant.now(), List.copyOf(results), List.copyOf(errors));
    }

    public IngestJob failed(String error) {
        return new IngestJob(jobId, Status.FAILED, startedAt, Instant.now(), results, List.of(error));
    }
}
