package com.bloom.eventmanager.ingestion.api.dto;

/** {@code source} restricts the run to one file name in the directory. */
public record IngestRequest(String source) {
}
