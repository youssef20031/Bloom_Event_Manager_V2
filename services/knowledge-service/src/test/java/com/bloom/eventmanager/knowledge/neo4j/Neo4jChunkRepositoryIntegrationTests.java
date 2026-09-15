package com.bloom.eventmanager.knowledge.neo4j;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.Driver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.neo4j.Neo4jContainer;

import com.bloom.eventmanager.knowledge.domain.ChunkMatch;
import com.bloom.eventmanager.knowledge.domain.ChunkRecord;
import com.bloom.eventmanager.knowledge.domain.DriveFileMetadata;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "spring.config.import=",
        "eureka.client.enabled=false",
        "spring.cloud.discovery.enabled=false",
        // Tiny vectors keep the fixtures readable; the index is created with this size.
        "bloom.knowledge.embedding-dimensions=4"
})
class Neo4jChunkRepositoryIntegrationTests {

    // Same image tag as deploy/docker-compose.yml, so behaviour matches what runs locally.
    // If Testcontainers rejects the calendar-versioned tag, use "neo4j:5" — every Cypher
    // statement here is valid on 5.15+.
    @Container
    @ServiceConnection
    static Neo4jContainer neo4j = new Neo4jContainer("neo4j:2026.06.0").withoutAuthentication();

    @Autowired
    private Neo4jChunkRepository repository;

    @Autowired
    private Driver driver;

    @BeforeEach
    void wipeGraph() {
        try (var session = driver.session()) {
            session.run("MATCH (n) DETACH DELETE n").consume();
        }
    }

    @Test
    void startupCreatedTheVectorIndexWithConfiguredDimensions() {
        assertThat(repository.vectorIndexExists("chunk_embeddings")).isTrue();
        assertThat(repository.vectorIndexExists("no_such_index")).isFalse();
    }

    @Test
    void storesChunksUnderTheirDocumentAndFindsThemByVectorSimilarity() {
        repository.storeBatch(List.of(
                new ChunkRecord("c-1", "team building games", "guide.pdf", 1, new float[] { 1f, 0f, 0f, 0f }),
                new ChunkRecord("c-2", "catering menus", "guide.pdf", 2, new float[] { 0f, 1f, 0f, 0f }),
                new ChunkRecord("c-3", "venue checklist", "venues.pdf", null, new float[] { 0f, 0f, 1f, 0f })));

        List<ChunkMatch> matches = repository.searchSimilar(new float[] { 0.9f, 0.1f, 0f, 0f }, 2);

        assertThat(matches).hasSize(2);
        assertThat(matches.get(0).id()).isEqualTo("c-1");
        assertThat(matches.get(0).source()).isEqualTo("guide.pdf");
        assertThat(matches.get(0).page()).isEqualTo(1);
        assertThat(matches.get(0).score()).isGreaterThan(matches.get(1).score());
        // V1 stored `page ?? 0`; a missing page reads back as 0, never as null.
        assertThat(repository.randomChunksFromSources(List.of("venues.pdf"), 10))
                .singleElement()
                .satisfies(match -> assertThat(match.page()).isZero());
    }

    @Test
    void storeBatchIsIdempotentOnChunkId() {
        ChunkRecord first = new ChunkRecord("c-1", "v1", "guide.pdf", 1, new float[] { 1f, 0f, 0f, 0f });
        ChunkRecord rewritten = new ChunkRecord("c-1", "v2", "guide.pdf", 1, new float[] { 1f, 0f, 0f, 0f });

        repository.storeBatch(List.of(first));
        repository.storeBatch(List.of(rewritten));

        List<ChunkMatch> all = repository.randomChunksFromSources(List.of("guide.pdf"), 10);
        assertThat(all).singleElement().satisfies(match -> assertThat(match.text()).isEqualTo("v2"));
    }

    @Test
    void listsSourcesFromDocumentNodesInOrder() {
        repository.storeBatch(List.of(
                new ChunkRecord("c-1", "x", "zeta.pdf", 1, new float[] { 1f, 0f, 0f, 0f }),
                new ChunkRecord("c-2", "y", "alpha.pdf", 1, new float[] { 0f, 1f, 0f, 0f })));

        assertThat(repository.allSources()).containsExactly("alpha.pdf", "zeta.pdf");
    }

    @Test
    void deletingADocumentRemovesItsChunksAndReportsTheCount() {
        repository.storeBatch(List.of(
                new ChunkRecord("c-1", "x", "guide.pdf", 1, new float[] { 1f, 0f, 0f, 0f }),
                new ChunkRecord("c-2", "y", "guide.pdf", 2, new float[] { 0f, 1f, 0f, 0f }),
                new ChunkRecord("c-3", "z", "other.pdf", 1, new float[] { 0f, 0f, 1f, 0f })));

        int deleted = repository.deleteDocument("guide.pdf");

        assertThat(deleted).isEqualTo(2);
        assertThat(repository.allSources()).containsExactly("other.pdf");
        assertThat(repository.deleteDocument("guide.pdf")).isZero();
    }

    @Test
    void tracksDriveFileMetadataOnTheDocumentNode() {
        repository.upsertDriveFileMetadata("brief.pdf", "drive-123", "2026-09-01T10:00:00.000Z");
        repository.upsertDriveFileMetadata("brief.pdf", "drive-123", "2026-09-02T10:00:00.000Z");

        Optional<DriveFileMetadata> metadata = repository.driveFileMetadata("drive-123");
        assertThat(metadata).contains(new DriveFileMetadata("brief.pdf", "2026-09-02T10:00:00.000Z"));
        assertThat(repository.driveFileMetadata("nope")).isEmpty();

        Map<String, String> all = repository.allDriveFileMetadata();
        assertThat(all).containsExactly(Map.entry("drive-123", "2026-09-02T10:00:00.000Z"));
    }

    @Test
    void driveMetadataAndChunksShareOneDocumentNode() {
        repository.upsertDriveFileMetadata("brief.pdf", "drive-123", "2026-09-01T10:00:00.000Z");
        repository.storeBatch(List.of(
                new ChunkRecord("c-1", "x", "brief.pdf", 1, new float[] { 1f, 0f, 0f, 0f })));

        try (var session = driver.session()) {
            long documents = session.run("MATCH (d:Document {id: 'brief.pdf'}) RETURN count(d) AS n")
                    .single().get("n").asLong();
            assertThat(documents).isEqualTo(1);
        }

        repository.deleteDocument("brief.pdf");
        assertThat(repository.driveFileMetadata("drive-123")).isEmpty();
    }
}
