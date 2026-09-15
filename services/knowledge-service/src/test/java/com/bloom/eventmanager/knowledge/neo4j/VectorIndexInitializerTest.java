package com.bloom.eventmanager.knowledge.neo4j;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

import com.bloom.eventmanager.knowledge.config.KnowledgeProperties;
import com.bloom.eventmanager.knowledge.domain.ChunkRepository;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VectorIndexInitializerTest {

    private final ChunkRepository repository = mock(ChunkRepository.class);
    private final KnowledgeProperties properties = new KnowledgeProperties("chunk_embeddings", 3072, "cosine");
    private final VectorIndexInitializer initializer = new VectorIndexInitializer(repository, properties);

    @Test
    void createsTheIndexWhenItIsMissing() {
        when(repository.vectorIndexExists("chunk_embeddings")).thenReturn(false);

        initializer.run(new DefaultApplicationArguments());

        verify(repository).createVectorIndex("chunk_embeddings", 3072, "cosine");
    }

    @Test
    void leavesAnExistingIndexAlone() {
        when(repository.vectorIndexExists("chunk_embeddings")).thenReturn(true);

        initializer.run(new DefaultApplicationArguments());

        verify(repository, never()).createVectorIndex(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyString());
    }
}
