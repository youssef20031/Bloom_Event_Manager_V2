package com.bloom.eventmanager.storage.mongo;

import java.util.List;

import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexField;
import org.springframework.data.mongodb.core.index.IndexInfo;
import org.springframework.data.mongodb.core.index.IndexOperations;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OAuthStateIndexInitializerTest {

    private final MongoTemplate template = mock(MongoTemplate.class);
    private final IndexOperations indexOps = mock(IndexOperations.class);
    private final OAuthStateIndexInitializer initializer = new OAuthStateIndexInitializer(template);

    @Test
    void createsTheExpiresAtTtlIndexWhenAbsent() {
        when(template.indexOps(OAuthStateDocument.class)).thenReturn(indexOps);
        when(indexOps.getIndexInfo()).thenReturn(List.of(idIndex()));

        initializer.run(null);

        verify(indexOps).createIndex(any());
    }

    /**
     * MongoDB rejects an identical index under a different name with error 85,
     * so a differently-named TTL index on the same field must still count.
     * Match on shape, not name.
     */
    @Test
    void doesNotRecreateAnEquivalentIndexWhateverItIsCalled() {
        when(template.indexOps(OAuthStateDocument.class)).thenReturn(indexOps);
        when(indexOps.getIndexInfo()).thenReturn(List.of(idIndex(), ttlIndexNamed("custom_name")));

        initializer.run(null);

        verify(indexOps, never()).createIndex(any());
    }

    @Test
    void recognisesSpringDatasOwnIndexNameToo() {
        when(template.indexOps(OAuthStateDocument.class)).thenReturn(indexOps);
        when(indexOps.getIndexInfo()).thenReturn(List.of(ttlIndexNamed("expiresAt")));

        initializer.run(null);

        verify(indexOps, never()).createIndex(any());
    }

    /**
     * A non-TTL index on expiresAt (no expireAfterSeconds) does not satisfy the
     * requirement -- it would never evict expired nonces.
     */
    @Test
    void aPlainNonTtlIndexOnExpiresAtDoesNotCount() {
        when(template.indexOps(OAuthStateDocument.class)).thenReturn(indexOps);
        when(indexOps.getIndexInfo())
                .thenReturn(List.of(new IndexInfo(List.of(IndexField.create("expiresAt", Sort.Direction.ASC)),
                        "expiresAt_1", false, false, "")));

        initializer.run(null);

        verify(indexOps).createIndex(any());
    }

    private static IndexInfo idIndex() {
        return new IndexInfo(List.of(IndexField.create("_id", Sort.Direction.ASC)), "_id_", false, false, "");
    }

    private static IndexInfo ttlIndexNamed(String name) {
        Document source = new Document();
        source.put("key", new Document("expiresAt", 1));
        source.put("name", name);
        source.put("expireAfterSeconds", 0);
        return IndexInfo.indexInfoOf(source);
    }

    /**
     * The whole point: an unreachable MongoDB must leave storage-service running,
     * the way DriveTokenIndexInitializer and MongoTokenStore.load already degrade.
     */
    @Test
    void anUnreachableMongoDbDoesNotPreventStartup() {
        when(template.indexOps(OAuthStateDocument.class))
                .thenThrow(new DataAccessResourceFailureException("Timed out while waiting for a server"));

        assertThatCode(() -> initializer.run(null)).doesNotThrowAnyException();
    }

    @Test
    void aFailingIndexCreationDoesNotPreventStartupEither() {
        when(template.indexOps(OAuthStateDocument.class)).thenReturn(indexOps);
        when(indexOps.getIndexInfo()).thenReturn(List.of(idIndex()));
        when(indexOps.createIndex(any())).thenThrow(new DataAccessResourceFailureException("Connection reset"));

        assertThatCode(() -> initializer.run(null)).doesNotThrowAnyException();
    }
}
