package com.bloom.eventmanager.ai.domain;

import java.util.List;

public interface EmbeddingPort {

    float[] embed(String text);

    /** Result is positionally aligned with {@code texts}. */
    List<float[]> embedBatch(List<String> texts);
}
