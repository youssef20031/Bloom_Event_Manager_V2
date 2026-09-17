package com.bloom.eventmanager.chat.domain;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.bloom.eventmanager.chat.client.AiClient;
import com.bloom.eventmanager.chat.client.RetrievalClient;
import com.bloom.eventmanager.chat.client.dto.ChunkMatch;
import com.bloom.eventmanager.chat.client.dto.GenerateRequest;
import com.bloom.eventmanager.chat.client.dto.GenerateResponse;
import com.bloom.eventmanager.chat.client.dto.RetrieveRequest;
import com.bloom.eventmanager.chat.config.ChatProperties;
import com.bloom.eventmanager.common.error.BloomException;
import com.bloom.eventmanager.common.error.ExternalServiceException;

/**
 * V1's ChatWithDocumentsUseCase: history → context → prompt → Gemini →
 * tag clean-up → persist. Prompt text is V1's verbatim.
 */
@Service
public class ChatWithDocumentsUseCase {

    private static final Pattern TEAMBUILDING = Pattern.compile("team\\s*building", Pattern.CASE_INSENSITIVE);
    private static final String TEAMBUILDING_QUERY =
            "Teambuilding activities Candy Love Caption This Spot the Movies Big Puzzle Age Lineup";
    private static final Pattern PLAN = Pattern.compile("<plan>([\\s\\S]*?)</plan>");
    private static final Pattern THINK = Pattern.compile("<think>([\\s\\S]*?)</think>");
    private static final String ALPHANUM = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final RetrievalClient retrieval;
    private final AiClient ai;
    private final ChatSessionStore store;
    private final ChatProperties properties;

    public ChatWithDocumentsUseCase(RetrievalClient retrieval, AiClient ai, ChatSessionStore store, ChatProperties properties) {
        this.retrieval = retrieval;
        this.ai = ai;
        this.store = store;
        this.properties = properties;
    }

    public ChatResult execute(String message, String requestedSessionId) {
        String sessionId = requestedSessionId == null || requestedSessionId.isBlank() ? newSessionId() : requestedSessionId;

        List<ChatMessage> history = store.recentMessages(sessionId, properties.historyLimit());

        List<ChunkMatch> chunks = new ArrayList<>(retrieve(message, properties.topK()));
        if (TEAMBUILDING.matcher(message).find()) {
            chunks.addAll(retrieve(TEAMBUILDING_QUERY, 10));
            chunks = dedupe(chunks);
        }

        List<String> sources = new ArrayList<>(new LinkedHashSet<>(chunks.stream().map(ChunkMatch::source).toList()));
        Map<String, Integer> sourceIndex = new LinkedHashMap<>();
        for (int i = 0; i < sources.size(); i++) {
            sourceIndex.put(sources.get(i), i + 1);
        }
        String context = chunks.stream()
                .map(c -> "[Source " + sourceIndex.get(c.source()) + ": " + c.source() + "]\n" + c.text())
                .collect(Collectors.joining("\n---\n"));

        GenerateResponse generated;
        try {
            generated = ai.generate(new GenerateRequest(buildPrompt(message, context, history),
                    new GenerateRequest.Config(true, 1.2, null)));
        }
        catch (BloomException ex) {
            // A peer's fallback already classified this -- notably
            // ServiceUnavailableException (503) when a circuit is open. Re-wrapping
            // would downgrade it to a 502 and tell the browser the wrong thing.
            throw ex;
        }
        catch (RuntimeException ex) {
            throw new ExternalServiceException("Event Advisor generation failed", ex);
        }

        String answer = generated.text() == null ? "" : generated.text();
        String thoughts = generated.thoughts();

        Matcher plan = PLAN.matcher(answer);
        if (plan.find()) {
            String content = plan.group(1).trim();
            String rawThoughts = PLAN.matcher(answer).replaceFirst("").trim();
            answer = content;
            if (!rawThoughts.isEmpty()) {
                thoughts = thoughts == null ? rawThoughts : thoughts + "\n\n---\n\n" + rawThoughts;
            }
        }
        else {
            Matcher think = THINK.matcher(answer);
            if (think.find()) {
                String inner = think.group(1).trim();
                thoughts = thoughts == null ? inner : thoughts + "\n\n---\n\n" + inner;
                answer = THINK.matcher(answer).replaceAll("").trim();
            }
        }

        if (answer.startsWith("Okay") || answer.startsWith("Sure") || answer.startsWith("Here")) {
            int split = answer.indexOf("\n\n");
            if (split != -1) {
                String fluff = answer.substring(0, split);
                if (fluff.length() < 200) {
                    if (thoughts == null) {
                        thoughts = fluff;
                    }
                    answer = answer.substring(split).trim();
                }
            }
        }

        Instant now = Instant.now();
        store.append(sessionId, List.of(
                new ChatMessage("user", message, now, List.of(), null),
                new ChatMessage("model", answer, now, sources, thoughts)));

        return new ChatResult(answer, thoughts, sessionId, sources);
    }

    private List<ChunkMatch> retrieve(String query, int topK) {
        try {
            List<ChunkMatch> chunks = retrieval.retrieve(new RetrieveRequest(query, topK, false)).chunks();
            return chunks == null ? List.of() : chunks;
        }
        catch (BloomException ex) {
            throw ex;
        }
        catch (RuntimeException ex) {
            throw new ExternalServiceException("Context retrieval failed", ex);
        }
    }

    private static List<ChunkMatch> dedupe(List<ChunkMatch> chunks) {
        Map<String, ChunkMatch> byId = new LinkedHashMap<>();
        for (ChunkMatch chunk : chunks) {
            byId.putIfAbsent(chunk.id(), chunk);
        }
        return new ArrayList<>(byId.values());
    }

    private static String buildPrompt(String message, String context, List<ChatMessage> history) {
        String previousActivities = history.stream()
                .filter(m -> "model".equals(m.role()))
                .map(ChatMessage::content)
                .collect(Collectors.joining("\n"));
        boolean hasPriorSuggestions = previousActivities.length() > 100;

        String antiRepetitionBlock = hasPriorSuggestions ? """

                CRITICAL — NO REPETITION:
                You have ALREADY suggested activities in this conversation. Review the conversation history above carefully.
                - Do NOT reuse, rename, or rephrase any activity you already proposed.
                - Every activity in your new response must be a COMPLETELY DIFFERENT concept.
                """ : "";

        String conversation = history.stream()
                .map(m -> m.role().toUpperCase() + ": " + m.content())
                .collect(Collectors.joining("\n"));

        return """
                You are an event planner for Bloom. You design fun, non-corporate events that focus on play and connection.

                KNOWLEDGE BASE (use as background inspiration ONLY — never copy directly):
                %s

                CONVERSATION SO FAR:
                %s

                USER: %s
                %s

                HOW YOU WORK:

                **Step 1 — Escapism**
                The theme and activities must not be related to the client's actual work or industry. Make people forget they are at a work event. Focus on fun, pop culture, mystery, or adventure.

                **Step 2 — Game Mechanics**
                Focus on games instead of physical activities. Use things like:
                - Card games
                - Board games
                - Puzzles and escape-room style riddles

                **Step 3 — Keep It Simple**
                Design activities that are easy to set up.
                - Explain activities in 1-2 sentences.
                - Use simple props like cards, paper, and locks. Do not use complex structures or tech setups.

                **Step 4 — Writing Style**
                - Use plain English and short sentences. Avoid corporate jargon.
                - Explain clearly what people will do.
                - Mix puzzles with relaxed social time.

                FORMATTING RULES:
                - If asked for a plan, use this structure:
                  * **Theme Concept**: A fun title and a short explanation.
                  * **Key Activities**: List 3 activities. Include "The Concept", "The Execution", and "Tools Needed".
                  * **Pacing & Integration**: Give a timeline of the event.
                  * **Food & Bev**: Suggest food and drinks that fit the theme.
                  * **Why This Fits**: Explain why this works for the client without mentioning their specific industry.
                - For timelines, use a table with "Time", "Activity", and "Details" columns.

                CITATION RULES:
                - Do not mention document names or file names inside activities.
                - Act like you came up with the ideas yourself.
                - At the very end, add a "Sources:" section with numbered references like so:
                  [1] Document_Name.pdf
                - Only use [x] markers at the end of paragraphs in the "Why This Fits" section.

                TONE: Helpful, creative, and clear.""".formatted(context, conversation, message, antiRepetitionBlock);
    }

    /** V1: `session_${Date.now()}_${Math.random().toString(36).substring(2, 9)}`. */
    static String newSessionId() {
        StringBuilder suffix = new StringBuilder(7);
        for (int i = 0; i < 7; i++) {
            suffix.append(ALPHANUM.charAt(RANDOM.nextInt(ALPHANUM.length())));
        }
        return "session_" + System.currentTimeMillis() + "_" + suffix;
    }
}
