package com.bloom.eventmanager.knowledge.neo4j;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import com.bloom.eventmanager.knowledge.config.KnowledgeProperties;
import com.bloom.eventmanager.knowledge.domain.ChunkRepository;

/**
 * Creates the vector index on startup if it does not exist. This is the
 * successor to V1's {@code npm run init-db}; there is no separate step to
 * forget any more.
 *
 * <p>An existing index is never altered. Changing dimensions means changing
 * the embedding model, which is a data migration, not a restart.
 */
@Component
public class VectorIndexInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(VectorIndexInitializer.class);

    private final ChunkRepository repository;
    private final KnowledgeProperties properties;

    public VectorIndexInitializer(ChunkRepository repository, KnowledgeProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        String name = properties.vectorIndexName();
        if (repository.vectorIndexExists(name)) {
            log.info("Vector index '{}' already exists", name);
            return;
        }
        log.info("Creating vector index '{}' ({} dimensions, {})", name,
                properties.embeddingDimensions(), properties.similarityFunction());
        repository.createVectorIndex(name, properties.embeddingDimensions(), properties.similarityFunction());
    }
}
