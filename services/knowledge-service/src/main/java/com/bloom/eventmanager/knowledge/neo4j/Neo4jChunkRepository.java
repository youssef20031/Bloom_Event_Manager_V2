package com.bloom.eventmanager.knowledge.neo4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;
import org.neo4j.driver.Value;
import org.springframework.stereotype.Repository;

import com.bloom.eventmanager.knowledge.config.KnowledgeProperties;
import com.bloom.eventmanager.knowledge.domain.ChunkMatch;
import com.bloom.eventmanager.knowledge.domain.ChunkRecord;
import com.bloom.eventmanager.knowledge.domain.ChunkRepository;
import com.bloom.eventmanager.knowledge.domain.DriveFileMetadata;

/**
 * Neo4j access using the graph shape V1's {@code Neo4jService} wrote:
 *
 * <pre>
 * (:Document {id, name, driveFileId?, driveModifiedTime?, lastSyncedAt?})
 *     -[:HAS_CHUNK]-> (:Chunk {id, text, source, page, embedding})
 * </pre>
 *
 * Keeping this shape is what lets Phase 1 validate against data V1 already
 * ingested. Do not "tidy" it into separate DriveFile nodes.
 */
@Repository
public class Neo4jChunkRepository implements ChunkRepository {

    private static final Pattern SAFE_INDEX_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private final Driver driver;
    private final KnowledgeProperties properties;

    public Neo4jChunkRepository(Driver driver, KnowledgeProperties properties) {
        this.driver = driver;
        this.properties = properties;
    }

    @Override
    public List<ChunkMatch> searchSimilar(float[] queryEmbedding, int topK) {
        String cypher = """
                CALL db.index.vector.queryNodes($indexName, $topK, $queryEmbedding)
                YIELD node, score
                RETURN node.id AS id, node.text AS text, node.source AS source, node.page AS page, score
                ORDER BY score DESC
                """;
        try (Session session = driver.session()) {
            return session.run(cypher, Map.of(
                    "indexName", properties.vectorIndexName(),
                    "topK", topK,
                    "queryEmbedding", toDoubleList(queryEmbedding)))
                    .list(Neo4jChunkRepository::toMatch);
        }
    }

    @Override
    public void storeBatch(List<ChunkRecord> chunks) {
        if (chunks.isEmpty()) {
            return;
        }

        List<String> sources = chunks.stream().map(ChunkRecord::source).distinct().toList();
        List<Map<String, Object>> rows = new ArrayList<>(chunks.size());
        for (ChunkRecord chunk : chunks) {
            Map<String, Object> row = new HashMap<>();
            row.put("id", chunk.id());
            row.put("text", chunk.text());
            row.put("source", chunk.source());
            row.put("page", chunk.page() == null ? 0 : chunk.page());
            row.put("embedding", toDoubleList(chunk.embedding()));
            rows.add(row);
        }

        try (Session session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run("""
                        UNWIND $sources AS source
                        MERGE (doc:Document {id: source})
                        ON CREATE SET doc.name = source
                        """, Map.of("sources", sources)).consume();
                tx.run("""
                        UNWIND $chunks AS chunk
                        MATCH (doc:Document {id: chunk.source})
                        MERGE (c:Chunk {id: chunk.id})
                        SET c.text = chunk.text,
                            c.embedding = chunk.embedding,
                            c.source = chunk.source,
                            c.page = chunk.page
                        MERGE (doc)-[:HAS_CHUNK]->(c)
                        """, Map.of("chunks", rows)).consume();
                return null;
            });
        }
    }

    @Override
    public boolean vectorIndexExists(String indexName) {
        String cypher = """
                SHOW INDEXES YIELD name, type
                WHERE name = $indexName AND type = 'VECTOR'
                RETURN name
                """;
        try (Session session = driver.session()) {
            return !session.run(cypher, Map.of("indexName", indexName)).list().isEmpty();
        }
    }

    @Override
    public void createVectorIndex(String indexName, int dimensions, String similarityFunction) {
        if (!SAFE_INDEX_NAME.matcher(indexName).matches()) {
            throw new IllegalArgumentException("Unsafe vector index name: " + indexName);
        }
        String cypher = """
                CREATE VECTOR INDEX %s IF NOT EXISTS
                FOR (c:Chunk) ON c.embedding
                OPTIONS { indexConfig: {
                    `vector.dimensions`: $dimensions,
                    `vector.similarity_function`: $similarityFunction
                } }
                """.formatted(indexName);
        try (Session session = driver.session()) {
            session.run(cypher, Map.of("dimensions", dimensions, "similarityFunction", similarityFunction)).consume();
        }
    }

    @Override
    public List<String> allSources() {
        try (Session session = driver.session()) {
            return session.run("MATCH (doc:Document) RETURN doc.name AS source ORDER BY source")
                    .list(record -> record.get("source").asString());
        }
    }

    @Override
    public List<ChunkMatch> randomChunksFromSources(List<String> sources, int limit) {
        String cypher = """
                MATCH (c:Chunk)
                WHERE c.source IN $sources
                WITH c, rand() AS r
                ORDER BY r
                LIMIT $limit
                RETURN c.id AS id, c.text AS text, c.source AS source, c.page AS page, 1.0 AS score
                """;
        try (Session session = driver.session()) {
            return session.run(cypher, Map.of("sources", sources, "limit", limit))
                    .list(Neo4jChunkRepository::toMatch);
        }
    }

    @Override
    public void upsertDriveFileMetadata(String source, String driveFileId, String modifiedTime) {
        String cypher = """
                MERGE (doc:Document {id: $source})
                ON CREATE SET doc.name = $source
                SET doc.driveFileId = $driveFileId,
                    doc.driveModifiedTime = $modifiedTime,
                    doc.lastSyncedAt = datetime()
                """;
        try (Session session = driver.session()) {
            session.run(cypher, Map.of("source", source, "driveFileId", driveFileId, "modifiedTime", modifiedTime))
                    .consume();
        }
    }

    @Override
    public Optional<DriveFileMetadata> driveFileMetadata(String driveFileId) {
        String cypher = """
                MATCH (doc:Document {driveFileId: $driveFileId})
                RETURN doc.name AS source, doc.driveModifiedTime AS modifiedTime
                LIMIT 1
                """;
        try (Session session = driver.session()) {
            List<Record> records = session.run(cypher, Map.of("driveFileId", driveFileId)).list();
            if (records.isEmpty()) {
                return Optional.empty();
            }
            Record record = records.get(0);
            return Optional.of(new DriveFileMetadata(
                    record.get("source").asString(),
                    record.get("modifiedTime").asString()));
        }
    }

    @Override
    public int deleteDocument(String source) {
        String cypher = """
                MATCH (doc:Document {id: $source})
                OPTIONAL MATCH (doc)-[:HAS_CHUNK]->(chunk:Chunk)
                DETACH DELETE doc, chunk
                RETURN count(chunk) AS deletedCount
                """;
        try (Session session = driver.session()) {
            List<Record> records = session.run(cypher, Map.of("source", source)).list();
            return records.isEmpty() ? 0 : records.get(0).get("deletedCount").asInt();
        }
    }

    @Override
    public Map<String, String> allDriveFileMetadata() {
        String cypher = """
                MATCH (doc:Document)
                WHERE doc.driveFileId IS NOT NULL
                RETURN doc.driveFileId AS fileId, doc.driveModifiedTime AS modifiedTime
                """;
        try (Session session = driver.session()) {
            Map<String, String> result = new LinkedHashMap<>();
            session.run(cypher).forEachRemaining(record ->
                    result.put(record.get("fileId").asString(), record.get("modifiedTime").asString("")));
            return result;
        }
    }

    private static ChunkMatch toMatch(Record record) {
        Value page = record.get("page");
        return new ChunkMatch(
                record.get("id").asString(),
                record.get("text").asString(""),
                record.get("source").asString(""),
                // V1 wrote `page` as a JS number, which the JS driver sends as a FLOAT.
                // asNumber() reads both INTEGER and FLOAT; asInt() would reject FLOAT.
                page.isNull() ? 0 : page.asNumber().intValue(),
                record.get("score").asDouble());
    }

    /** The driver has no float[] mapping; Cypher LIST<FLOAT> is 64-bit anyway. */
    private static List<Double> toDoubleList(float[] embedding) {
        List<Double> list = new ArrayList<>(embedding.length);
        for (float f : embedding) {
            list.add((double) f);
        }
        return list;
    }
}
