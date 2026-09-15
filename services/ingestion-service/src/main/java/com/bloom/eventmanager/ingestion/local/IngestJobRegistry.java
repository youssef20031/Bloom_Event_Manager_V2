package com.bloom.eventmanager.ingestion.local;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/** In-memory, like V1's sync bookkeeping. Restarting the service forgets jobs; that is acceptable. */
@Component
public class IngestJobRegistry {

    private final Map<String, IngestJob> jobs = new ConcurrentHashMap<>();

    public void put(IngestJob job) {
        jobs.put(job.jobId(), job);
    }

    public Optional<IngestJob> get(String jobId) {
        return Optional.ofNullable(jobs.get(jobId));
    }
}
