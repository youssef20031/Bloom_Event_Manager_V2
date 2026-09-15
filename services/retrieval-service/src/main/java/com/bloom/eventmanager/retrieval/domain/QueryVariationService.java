package com.bloom.eventmanager.retrieval.domain;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import com.bloom.eventmanager.common.ai.JsonExtractor;
import com.bloom.eventmanager.retrieval.client.AiClient;
import com.bloom.eventmanager.retrieval.client.dto.GenerateRequest;
import com.bloom.eventmanager.retrieval.client.dto.GenerateResponse;

/**
 * Asks Gemini to split a query into a client-specific and a general-knowledge
 * variant. The prompt is V1's verbatim.
 *
 * <p>Cached for an hour per query: the prompt is a fixed template over the
 * query alone, so the result is deterministic. Failures throw and are
 * therefore <em>not</em> cached — Spring Cache never stores an exception — so
 * one transient Gemini error cannot pin a query to its degraded form.
 */
@Service
public class QueryVariationService {

    private final AiClient ai;
    private final JsonMapper json;

    public QueryVariationService(AiClient ai, JsonMapper json) {
        this.ai = ai;
        this.json = json;
    }

    @Cacheable(cacheNames = "queryVariations", key = "#query")
    public SearchVariations variations(String query) {
        String prompt = """
                Analyze this search query: "%s"

                Goal: We need to find both specific client documents AND general knowledge base articles (like activity guides, best practices).

                Task: Generate two search queries:
                1. "Specific": Optimized to find client briefs, previous chats, and specific constraints. (Keep names like "Awlady", "Bloom").
                2. "Broad": Optimized to find general concepts, removing proper nouns that might not exist in the general knowledge base. (e.g. change "Awlady team building" to "non-profit team building activities").

                Format: Return ONLY a JSON object: {"specific": "...", "broad": "..."}""".formatted(query);

        GenerateResponse response = ai.generate(new GenerateRequest(prompt));
        SearchVariations parsed = json.readValue(JsonExtractor.extract(response.text()), SearchVariations.class);

        return new SearchVariations(
                hasText(parsed.specific()) ? parsed.specific() : query,
                hasText(parsed.broad()) ? parsed.broad() : query);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
