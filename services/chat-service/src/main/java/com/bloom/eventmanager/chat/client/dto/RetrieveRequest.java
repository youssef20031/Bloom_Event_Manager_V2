package com.bloom.eventmanager.chat.client.dto;

/** {@code useAI} keeps V1's wire spelling. Chat always sends false: V1's chat used a plain single-vector search. */
public record RetrieveRequest(String query, int topK, boolean useAI) {
}
