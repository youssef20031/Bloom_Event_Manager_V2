package com.bloom.eventmanager.chat.domain;

import java.util.List;

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

class ChatSessionIndexInitializerTest {

    private final MongoTemplate template = mock(MongoTemplate.class);
    private final IndexOperations indexOps = mock(IndexOperations.class);
    private final ChatSessionIndexInitializer initializer = new ChatSessionIndexInitializer(template);

    @Test
    void createsTheUniqueSessionIdIndexWhenAbsent() {
        when(template.indexOps(ChatSessionDocument.class)).thenReturn(indexOps);
        when(indexOps.getIndexInfo()).thenReturn(List.of(idIndex()));

        initializer.run(null);

        verify(indexOps).createIndex(any());
    }

    /**
     * MongoDB rejects an identical index under a different name with error 85,
     * and the name differs by origin: Spring Data writes {@code sessionId},
     * V1's Mongoose writes {@code sessionId_1}. Match on shape, not name.
     */
    @Test
    void doesNotRecreateAnEquivalentIndexWhateverItIsCalled() {
        when(template.indexOps(ChatSessionDocument.class)).thenReturn(indexOps);
        when(indexOps.getIndexInfo()).thenReturn(List.of(idIndex(), uniqueSessionIdIndexNamed("sessionId_1")));

        initializer.run(null);

        verify(indexOps, never()).createIndex(any());
    }

    @Test
    void recognisesSpringDatasOwnIndexNameToo() {
        when(template.indexOps(ChatSessionDocument.class)).thenReturn(indexOps);
        when(indexOps.getIndexInfo()).thenReturn(List.of(uniqueSessionIdIndexNamed("sessionId")));

        initializer.run(null);

        verify(indexOps, never()).createIndex(any());
    }

    private static IndexInfo idIndex() {
        return new IndexInfo(List.of(IndexField.create("_id", Sort.Direction.ASC)), "_id_", false, false, "");
    }

    private static IndexInfo uniqueSessionIdIndexNamed(String name) {
        return new IndexInfo(List.of(IndexField.create("sessionId", Sort.Direction.ASC)), name, true, false, "");
    }

    /**
     * The whole point: an unreachable MongoDB must leave chat-service running
     * instead of aborting context refresh.
     */
    @Test
    void anUnreachableMongoDbDoesNotPreventStartup() {
        when(template.indexOps(ChatSessionDocument.class))
                .thenThrow(new DataAccessResourceFailureException("Timed out while waiting for a server"));

        assertThatCode(() -> initializer.run(null)).doesNotThrowAnyException();
    }

    @Test
    void aFailingIndexCreationDoesNotPreventStartupEither() {
        when(template.indexOps(ChatSessionDocument.class)).thenReturn(indexOps);
        when(indexOps.getIndexInfo()).thenReturn(List.of(idIndex()));
        when(indexOps.createIndex(any())).thenThrow(new DataAccessResourceFailureException("Connection reset"));

        assertThatCode(() -> initializer.run(null)).doesNotThrowAnyException();
    }
}
