package com.bloom.eventmanager.ingestion.domain;

public interface OcrEngine {

    boolean available();

    /** Recognised text across all pages; empty when nothing was recognised. */
    String recognize(byte[] pdf);
}
