package com.bloom.eventmanager.proposal.domain;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import com.bloom.eventmanager.common.ai.JsonExtractor;
import com.bloom.eventmanager.common.error.BloomException;
import com.bloom.eventmanager.common.error.ErrorCode;
import com.bloom.eventmanager.common.error.ExternalServiceException;
import com.bloom.eventmanager.common.error.NotFoundException;
import com.bloom.eventmanager.proposal.client.AiClient;
import com.bloom.eventmanager.proposal.client.ChatClient;
import com.bloom.eventmanager.proposal.client.KnowledgeClient;
import com.bloom.eventmanager.proposal.client.dto.ChatSession;
import com.bloom.eventmanager.proposal.client.dto.ChunkMatch;
import com.bloom.eventmanager.proposal.client.dto.ChunkSearchRequest;
import com.bloom.eventmanager.proposal.client.dto.ChunksBySourcesRequest;
import com.bloom.eventmanager.proposal.client.dto.EmbedRequest;
import com.bloom.eventmanager.proposal.client.dto.GenerateRequest;
import com.bloom.eventmanager.proposal.client.dto.GenerateResponse;

/** V1's GenerateProposalUseCase, step for step, prompt verbatim. */
@Service
public class GenerateProposalUseCase {

    private static final Logger log = LoggerFactory.getLogger(GenerateProposalUseCase.class);
    private static final DateTimeFormatter SESSION_DATE = DateTimeFormatter.ofPattern("M/d/yyyy").withZone(ZoneId.systemDefault());

    private final KnowledgeClient knowledge;
    private final AiClient ai;
    private final ChatClient chat;
    private final JsonMapper json;

    public GenerateProposalUseCase(KnowledgeClient knowledge, AiClient ai, ChatClient chat, JsonMapper json) {
        this.knowledge = knowledge;
        this.ai = ai;
        this.chat = chat;
        this.json = json;
    }

    public GeneratedProposal execute(String companyName) {
        if (companyName == null || companyName.isBlank()) {
            throw new BloomException(ErrorCode.VALIDATION_FAILED, "Company name is required");
        }
        log.info("Generating AI proposal for: {}", companyName);

        // Step 1: sources whose name contains the company (case-insensitive)
        String needle = companyName.toLowerCase();
        List<String> companySources = safely(knowledge::allSources, "knowledge-service").stream()
                .filter(s -> s.toLowerCase().contains(needle))
                .toList();
        if (companySources.isEmpty()) {
            throw new NotFoundException("No data found for \"" + companyName + "\". Make sure a brief has been submitted and synced.");
        }

        // Step 2: text chunks from those sources
        List<ChunkMatch> chunks = safely(() -> knowledge.chunksBySources(new ChunksBySourcesRequest(companySources, 50)), "knowledge-service");
        String contextText = chunks.stream().map(ChunkMatch::text).collect(Collectors.joining("\n\n---\n\n"));

        // Step 3: Event Advisor conversations mentioning the company — best effort, as in V1
        String chatContext = "";
        try {
            StringBuilder sb = new StringBuilder();
            for (ChatSession session : chat.sessionsMentioning(companyName)) {
                String convo = session.messages().stream()
                        .map(m -> ("user".equals(m.role()) ? "CLIENT" : "ADVISOR") + ": " + m.content())
                        .collect(Collectors.joining("\n"));
                sb.append("\n\n=== Chat Session (").append(SESSION_DATE.format(session.createdAt())).append(") ===\n").append(convo);
            }
            chatContext = sb.toString();
        }
        catch (RuntimeException ex) {
            log.warn("Could not retrieve chat history, continuing without it: {}", ex.getMessage(), ex);
        }

        // Step 4: teambuilding knowledge via a vector search
        float[] embedding = safely(() -> ai.embed(new EmbedRequest(companyName + " event teambuilding activities agenda proposal")), "ai-service").embedding();
        List<ChunkMatch> tbChunks = safely(() -> knowledge.search(new ChunkSearchRequest(embedding, 10)), "knowledge-service");
        String tbContext = tbChunks.stream().map(c -> "[" + c.source() + "] " + c.text()).collect(Collectors.joining("\n---\n"));

        // Step 5: generate and parse
        String prompt = buildPrompt(companyName, contextText, tbContext, chatContext);
        GenerateResponse response = safely(() -> ai.generate(new GenerateRequest(
                prompt, new GenerateRequest.Config(null, 0.7, 8192))), "ai-service");

        List<ProposalSlide> slides = parseSlides(response.text());
        List<ProposalSlide> numbered = new ArrayList<>();
        for (int i = 0; i < slides.size(); i++) {
            ProposalSlide slide = slides.get(i).withNumber(i + 1);
            if (i == 0 && "cover".equals(slide.type())) {
                Map<String, Object> cover = new HashMap<>(slide.content() == null ? Map.of() : slide.content());
                cover.put("companyName", companyName);
                cover.put("preparedBy", "Bloom Events");
                slide = slide.withContent(cover);
            }
            numbered.add(slide);
        }
        log.info("Proposal generated for {}: {} slides", companyName, numbered.size());
        return new GeneratedProposal(numbered, companyName, Instant.now().toString());
    }

    private List<ProposalSlide> parseSlides(String text) {
        try {
            JsonNode root = json.readTree(JsonExtractor.extract(text));
            JsonNode array = root.isArray() ? root : root.get("slides");
            if (array == null || !array.isArray()) {
                throw new IllegalArgumentException("no slides array");
            }
            // slideNumber is renumbered right below regardless of what the model
            // emitted, so a model response that omits it should not fail parsing.
            return json.reader()
                    .forType(json.getTypeFactory().constructCollectionType(List.class, ProposalSlide.class))
                    .without(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                    .readValue(json.writeValueAsString(array));
        }
        catch (RuntimeException ex) {
            log.error("Failed to parse AI proposal response; first 500 chars: {}", text == null ? "" : text.substring(0, Math.min(500, text.length())));
            throw new BloomException(ErrorCode.INTERNAL_ERROR, "Failed to generate proposal. AI response was not valid JSON.", ex);
        }
    }

    private static <T> T safely(java.util.function.Supplier<T> call, String service) {
        try {
            return call.get();
        }
        catch (BloomException ex) {
            throw ex;
        }
        catch (RuntimeException ex) {
            throw new ExternalServiceException(service + " call failed", ex);
        }
    }

    static String buildPrompt(String companyName, String clientContext, String teambuildingContext, String chatContext) {
        String chatSection = chatContext.isEmpty() ? "" : """

                ═══════════════════════════════════════
                EVENT ADVISOR CONVERSATIONS (discussions about %s):
                ═══════════════════════════════════════
                %s
                """.formatted(companyName, chatContext);

        return """
                You are an expert Event Proposal Writer for Bloom, a premium event management company. Your task is to create a professional, compelling slide-based proposal that a client can present to THEIR client or stakeholders.

                You have access to the client's brief data, Event Advisor conversations, and knowledge about teambuilding activities. Use ALL the information below to create a tailored, detailed proposal.

                ═══════════════════════════════════════
                CLIENT BRIEF & EVENT DATA (from "%s"):
                ═══════════════════════════════════════
                %s
                %s
                ═══════════════════════════════════════
                TEAMBUILDING KNOWLEDGE & ACTIVITIES:
                ═══════════════════════════════════════
                %s

                ═══════════════════════════════════════
                YOUR TASK:
                ═══════════════════════════════════════
                Generate EXACTLY 9 slides as a JSON object. Extract all relevant information from the client brief AND the Event Advisor conversations above — company name, event dates, goals, participants, venue preferences, catering needs, cultural considerations, any specific requests discussed in chat, etc.

                Then combine that with the teambuilding knowledge to create specific, actionable activity recommendations.

                RESPOND WITH ONLY A JSON OBJECT in this exact structure:
                {
                  "slides": [
                    {
                      "slideNumber": 1,
                      "type": "cover",
                      "title": "%s",
                      "subtitle": "Event Proposal",
                      "content": {
                        "companyName": "%s",
                        "eventDate": "<extract from brief or say TBD>",
                        "tagline": "<creative tagline for the event based on their goals/values>",
                        "preparedBy": "Bloom Events"
                      }
                    },
                    {
                      "slideNumber": 2,
                      "type": "summary",
                      "title": "Executive Summary",
                      "content": {
                        "overview": "<3-4 sentences summarizing the proposed event based on their actual brief data>",
                        "highlights": ["<highlight 1>", "<highlight 2>", "<highlight 3>", "<highlight 4>"]
                      }
                    },
                    {
                      "slideNumber": 3,
                      "type": "concept",
                      "title": "Event Concept & Theme",
                      "content": {
                        "themeName": "<creative theme name inspired by their values/culture>",
                        "description": "<2-3 sentences describing the theme>",
                        "connectionToValues": "<how this connects to their stated core values/culture>"
                      }
                    },
                    {
                      "slideNumber": 4,
                      "type": "agenda",
                      "title": "Proposed Agenda",
                      "content": {
                        "items": [
                          { "time": "<e.g. 9:00 AM>", "activity": "<name>", "description": "<brief>" }
                        ]
                      }
                    },
                    {
                      "slideNumber": 5,
                      "type": "activities",
                      "title": "Teambuilding Activities",
                      "content": {
                        "activities": [
                          { "name": "<use REAL activity names from the knowledge base>", "description": "<actual description>", "duration": "<e.g. 45 min>", "participants": "<e.g. Teams of 5-6>" }
                        ]
                      }
                    },
                    {
                      "slideNumber": 6,
                      "type": "venue",
                      "title": "Venue & Logistics",
                      "content": {
                        "venueType": "<from brief>",
                        "capacity": "<from brief>",
                        "setup": ["<requirement 1>", "<requirement 2>"],
                        "notes": "<from brief venue notes>"
                      }
                    },
                    {
                      "slideNumber": 7,
                      "type": "catering",
                      "title": "Catering & Amenities",
                      "content": {
                        "meals": [
                          { "name": "<meal name>", "description": "<details>" }
                        ],
                        "dietaryNotes": "<from brief dietary restrictions>",
                        "amenities": ["<from brief>"]
                      }
                    },
                    {
                      "slideNumber": 8,
                      "type": "investment",
                      "title": "Investment Summary",
                      "content": {
                        "categories": [
                          { "category": "<e.g. Venue>", "description": "<what's included>", "tier": "<Standard/Premium/Custom>" }
                        ],
                        "note": "<general note about pricing>"
                      }
                    },
                    {
                      "slideNumber": 9,
                      "type": "closing",
                      "title": "Why Bloom",
                      "content": {
                        "message": "<compelling closing based on their specific needs>",
                        "valueProps": ["<value prop 1>", "<value prop 2>", "<value prop 3>"],
                        "contactInfo": "Let's create something extraordinary together."
                      }
                    }
                  ]
                }

                CRITICAL RULES:
                - Extract REAL data from the client brief AND chat conversations above (dates, participants, goals, venue, etc.)
                - If the Event Advisor conversations contain specific requests or preferences, incorporate them
                - Use REAL teambuilding activity names from the knowledge base (e.g., "Candy Love", "Caption This", etc.)
                - Be specific and tailored — do NOT use generic placeholder text
                - Output ONLY valid JSON. No markdown, no explanation, no code fences.""".formatted(
                companyName, clientContext, chatSection, teambuildingContext, companyName, companyName);
    }
}
