package com.bloom.eventmanager.retrieval.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import com.bloom.eventmanager.common.ai.JsonExtractor;
import com.bloom.eventmanager.retrieval.client.AiClient;
import com.bloom.eventmanager.retrieval.client.dto.ChunkMatch;
import com.bloom.eventmanager.retrieval.client.dto.GenerateRequest;
import com.bloom.eventmanager.retrieval.client.dto.GenerateResponse;

/**
 * Asks Gemini to order candidate chunks by relevance beyond vector similarity.
 * V1's prompt verbatim. Any failure returns the candidates in their incoming
 * (vector-score) order: re-ranking is a refinement, never a dependency.
 */
@Service
public class ReRankService {

    private static final Logger log = LoggerFactory.getLogger(ReRankService.class);
    private static final int PREVIEW_CHARS = 300;

    private final AiClient ai;
    private final JsonMapper json;

    public ReRankService(AiClient ai, JsonMapper json) {
        this.ai = ai;
        this.json = json;
    }

    public List<ChunkMatch> reRank(String query, List<ChunkMatch> chunks) {
        if (chunks.isEmpty()) {
            return chunks;
        }

        Map<String, ChunkMatch> byId = new LinkedHashMap<>();
        for (ChunkMatch chunk : chunks) {
            byId.putIfAbsent(chunk.id(), chunk);
        }
        List<ChunkMatch> unique = new ArrayList<>(byId.values());

        try {
            GenerateResponse response = ai.generate(new GenerateRequest(buildPrompt(query, unique)));
            Ranking ranking = json.readValue(JsonExtractor.extract(response.text()), Ranking.class);
            if (ranking.ranking() == null || ranking.ranking().isEmpty()) {
                return unique;
            }

            Set<Integer> seen = new LinkedHashSet<>();
            List<ChunkMatch> ordered = new ArrayList<>();
            for (Integer oneBased : ranking.ranking()) {
                if (oneBased == null || oneBased < 1 || oneBased > unique.size() || !seen.add(oneBased)) {
                    continue;
                }
                ordered.add(unique.get(oneBased - 1));
            }
            return ordered.isEmpty() ? unique : ordered;
        }
        catch (RuntimeException ex) {
            log.warn("Re-ranking failed, using vector order: {}", ex.getMessage());
            return unique;
        }
    }

    private static String buildPrompt(String query, List<ChunkMatch> unique) {
        StringBuilder results = new StringBuilder();
        for (int i = 0; i < unique.size(); i++) {
            ChunkMatch chunk = unique.get(i);
            String text = chunk.text() == null ? "" : chunk.text();
            String preview = text.length() > PREVIEW_CHARS ? text.substring(0, PREVIEW_CHARS) : text;
            results.append("\n[").append(i + 1).append("]\nSource: ").append(chunk.source())
                    .append("\nContent: ").append(preview).append("...\n");
        }

        return """
                Query: "%s"

                Rank these search results by relevance (1 = most relevant).
                Rules:
                - Prioritize "General Knowledge" (Activities, Guides) if the user is asking for ideas.
                - Prioritize "Client Context" (Briefs) if the user is asking about constraints.
                - Down-rank generic "Chat Logs" unless they contain specific decided ideas.

                Results:
                %s
                Return ONLY a JSON array of numbers representing the ranking order.
                Format: {"ranking": [3, 1, 5, 2, 4]}""".formatted(query, results);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Ranking(List<Integer> ranking) {
    }
}
