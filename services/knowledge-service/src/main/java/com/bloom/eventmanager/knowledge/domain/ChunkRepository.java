package com.bloom.eventmanager.knowledge.domain;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface ChunkRepository {

    List<ChunkMatch> searchSimilar(float[] queryEmbedding, int topK);

    void storeBatch(List<ChunkRecord> chunks);

    boolean vectorIndexExists(String indexName);

    void createVectorIndex(String indexName, int dimensions, String similarityFunction);

    List<String> allSources();

    List<ChunkMatch> randomChunksFromSources(List<String> sources, int limit);

    void upsertDriveFileMetadata(String source, String driveFileId, String modifiedTime);

    Optional<DriveFileMetadata> driveFileMetadata(String driveFileId);

    /** @return number of chunks removed; 0 when the document did not exist */
    int deleteDocument(String source);

    /** @return driveFileId -> modifiedTime for every Document that came from Drive */
    Map<String, String> allDriveFileMetadata();
}
