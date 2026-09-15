package com.bloom.eventmanager.events;

public final class BloomTopics {

    public static final String DOCUMENT_DISCOVERED = "bloom.documents.discovered";
    public static final String CHUNK_BATCH_READY = "bloom.chunks.batch-ready";
    public static final String DOCUMENT_INDEXED = "bloom.documents.indexed";
    public static final String DOCUMENT_DELETED = "bloom.documents.deleted";

    private BloomTopics() {
    }
}
