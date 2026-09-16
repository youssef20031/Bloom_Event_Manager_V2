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
 * Creates the unique {@code accountId} index without making MongoDB a startup
 * dependency.
 *
 * <p>Spring Data's {@code spring.data.mongodb.auto-index-creation} builds this
 * index during context refresh, so an unreachable MongoDB aborts startup with a
 * {@code BeanCreationException} from {@code CreateIndexesOperation} — long
 * before {@link MongoTokenStore#load} gets to swallow the failure and degrade to
 * "unauthenticated". Phase 3b verification hit exactly that while pointing at
 * V1's Atlas cluster: storage-service refused to start at all instead of
 * starting without Drive. V1 logs a warning and carries on, and so does this.
 *
 * <p>Creating an index is idempotent when one of the same shape already exists,
 * so this is safe to run against a database V1 already populated.
 */
@Component
public class DriveTokenIndexInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DriveTokenIndexInitializer.class);

    private final MongoTemplate template;

    public DriveTokenIndexInitializer(MongoTemplate template) {
        this.template = template;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            IndexOperations indexes = template.indexOps(DriveTokenDocument.class);
            if (hasUniqueAccountIdIndex(indexes)) {
                log.debug("Unique accountId index on drivetokens already present");
                return;
            }
            indexes.createIndex(new Index().on("accountId", Sort.Direction.ASC).unique());
            log.info("Created unique accountId index on drivetokens");
        }
        catch (RuntimeException ex) {
            log.warn("Could not create the drivetokens accountId index — continuing without it: {}", ex.getMessage());
        }
    }

    /**
     * Matched on shape, never on name. MongoDB rejects an otherwise-identical
     * index under a different name with error 85 (IndexOptionsConflict), and the
     * name varies by who created the collection: Spring Data's auto-index
     * creation uses {@code accountId}, V1's Mongoose uses {@code accountId_1}.
     */
    private static boolean hasUniqueAccountIdIndex(IndexOperations indexes) {
        return indexes.getIndexInfo().stream()
                .filter(IndexInfo::isUnique)
                .anyMatch(index -> index.getIndexFields().size() == 1
                        && "accountId".equals(index.getIndexFields().get(0).getKey()));
    }
}
