package com.bloom.eventmanager.chat.domain;

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
 * Creates the unique {@code sessionId} index without making MongoDB a startup
 * dependency.
 *
 * <p>Spring Data's {@code spring.data.mongodb.auto-index-creation} builds this
 * index during context refresh, so an unreachable MongoDB aborts startup with a
 * {@code BeanCreationException} from {@code CreateIndexesOperation} — the same
 * failure mode storage-service's {@code DriveTokenIndexInitializer} exists to
 * avoid. chat-service.yml documents reusing V1's {@code chatdb} as the primary
 * deployment mode, and that database's {@code chatsessions} collection already
 * carries a unique index on {@code sessionId} named {@code sessionId_1} by
 * Mongoose's convention — a name auto-index-creation would not have produced
 * unassisted before {@link ChatSessionDocument#getSessionId() sessionId}'s
 * {@code useGeneratedName = true} was added, and which MongoDB refuses to
 * register a second time under a different name (error 85,
 * IndexOptionsConflict) regardless.
 *
 * <p>Creating an index is idempotent when one of the same shape already
 * exists, so this is safe to run against a database V1 already populated, and
 * it still creates the index correctly for a brand-new deployment that has
 * never seen V1's data.
 */
@Component
public class ChatSessionIndexInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ChatSessionIndexInitializer.class);

    private final MongoTemplate template;

    public ChatSessionIndexInitializer(MongoTemplate template) {
        this.template = template;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            IndexOperations indexes = template.indexOps(ChatSessionDocument.class);
            if (hasUniqueSessionIdIndex(indexes)) {
                log.debug("Unique sessionId index on chatsessions already present");
                return;
            }
            indexes.createIndex(new Index().on("sessionId", Sort.Direction.ASC).unique());
            log.info("Created unique sessionId index on chatsessions");
        }
        catch (RuntimeException ex) {
            log.warn("Could not create the chatsessions sessionId index — continuing without it: {}", ex.getMessage());
        }
    }

    /**
     * Matched on shape, never on name. MongoDB rejects an otherwise-identical
     * index under a different name with error 85 (IndexOptionsConflict), and
     * the name varies by who created the collection: Spring Data's
     * auto-index-creation uses {@code sessionId}, V1's Mongoose uses
     * {@code sessionId_1}.
     */
    private static boolean hasUniqueSessionIdIndex(IndexOperations indexes) {
        return indexes.getIndexInfo().stream()
                .filter(IndexInfo::isUnique)
                .anyMatch(index -> index.getIndexFields().size() == 1
                        && "sessionId".equals(index.getIndexFields().get(0).getKey()));
    }
}
