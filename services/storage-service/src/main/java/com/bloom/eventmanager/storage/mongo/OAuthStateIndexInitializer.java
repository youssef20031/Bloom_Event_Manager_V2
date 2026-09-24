package com.bloom.eventmanager.storage.mongo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.IndexInfo;
import org.springframework.data.mongodb.core.index.IndexOperations;
import org.springframework.stereotype.Component;

/**
 * Creates the TTL {@code expiresAt} index on {@code oauthstates} without
 * making MongoDB a startup dependency.
 *
 * <p>storage-service runs with {@code spring.data.mongodb.auto-index-creation}
 * off (see {@link DriveTokenIndexInitializer}), so the {@code @Indexed} on
 * {@link OAuthStateDocument#expiresAt()} never creates anything by itself.
 * This mirrors {@link DriveTokenIndexInitializer}: an unreachable MongoDB logs
 * a warning and storage-service keeps starting, rather than aborting startup
 * with a {@code BeanCreationException}.
 *
 * <p>Creating an index is idempotent when one of the same shape already
 * exists, so this is safe to run every time the service starts.
 */
@Component
public class OAuthStateIndexInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(OAuthStateIndexInitializer.class);

    private final MongoTemplate template;

    public OAuthStateIndexInitializer(MongoTemplate template) {
        this.template = template;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            IndexOperations indexes = template.indexOps(OAuthStateDocument.class);
            if (hasExpiresAtTtlIndex(indexes)) {
                log.debug("TTL expiresAt index on oauthstates already present");
                return;
            }
            indexes.createIndex(new Index().on("expiresAt", Sort.Direction.ASC).expire(0));
            log.info("Created TTL expiresAt index on oauthstates");
        }
        catch (RuntimeException ex) {
            log.warn("Could not create the oauthstates expiresAt TTL index — continuing without it: {}", ex.getMessage());
        }
    }

    /**
     * Matched on shape, never on name. MongoDB rejects an otherwise-identical
     * index under a different name with error 85 (IndexOptionsConflict), and the
     * name varies by who created the collection.
     */
    private static boolean hasExpiresAtTtlIndex(IndexOperations indexes) {
        return indexes.getIndexInfo().stream()
                .filter(index -> index.getExpireAfter().isPresent())
                .anyMatch(index -> index.getIndexFields().size() == 1
                        && "expiresAt".equals(index.getIndexFields().get(0).getKey()));
    }
}
