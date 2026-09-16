package com.bloom.eventmanager.ingestion.domain;

public interface TextExtractor {

    /** Text layer of the PDF; empty when there is none or the bytes are not a PDF. */
    String extract(byte[] pdf);
}
