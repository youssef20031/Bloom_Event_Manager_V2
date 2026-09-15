package com.bloom.eventmanager.ai.gemini;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.stereotype.Service;

import com.bloom.eventmanager.ai.config.AiProperties;
import com.bloom.eventmanager.ai.domain.GenerationOptions;
import com.bloom.eventmanager.ai.domain.GenerationPort;
import com.bloom.eventmanager.ai.domain.GenerationResult;
import com.bloom.eventmanager.common.error.ExternalServiceException;

/**
 * Text generation with V1's two behaviours: walk a model chain on quota /
 * unknown-model errors, and split Gemini's "thought" parts from the answer.
 *
 * <p>Generation is deliberately uncached (V1 too): the chat path runs at
 * temperature 1.2 with a "never repeat a previous suggestion" instruction,
 * which a cache would quietly break.
 */
@Service
public class GeminiGenerationService implements GenerationPort {

    private static final Logger log = LoggerFactory.getLogger(GeminiGenerationService.class);

    private final ChatModel chatModel;
    private final List<String> modelChain;

    public GeminiGenerationService(ChatModel chatModel, AiProperties properties) {
        this.chatModel = chatModel;
        LinkedHashSet<String> chain = new LinkedHashSet<>();
        chain.add(properties.model());
        chain.addAll(properties.fallbackModels());
        this.modelChain = List.copyOf(chain);
    }

    @Override
    public GenerationResult generate(String input, GenerationOptions options) {
        RuntimeException last = null;
        for (String model : modelChain) {
            try {
                ChatResponse response = chatModel.call(new Prompt(input, toChatOptions(model, options)));
                return split(response);
            }
            catch (RuntimeException ex) {
                last = ex;
                if (GeminiErrors.shouldTryNextModel(ex)) {
                    log.warn("Model {} unavailable ({}); trying the next model in the chain",
                            model, GeminiErrors.statusCode(ex).orElse(-1));
                    continue;
                }
                throw new ExternalServiceException("Gemini generation failed with model " + model, ex);
            }
        }
        throw new ExternalServiceException("Gemini generation failed after trying all models", last);
    }

    private static GoogleGenAiChatOptions toChatOptions(String model, GenerationOptions options) {
        GoogleGenAiChatOptions.Builder builder = GoogleGenAiChatOptions.builder().model(model);
        if (options.temperature() != null) {
            builder.temperature(options.temperature());
        }
        if (options.maxOutputTokens() != null) {
            builder.maxOutputTokens(options.maxOutputTokens());
        }
        if (Boolean.TRUE.equals(options.includeThoughts())) {
            builder.includeThoughts(true);
        }
        return builder.build();
    }

    /** Spring AI yields one Generation per Gemini part, each flagged isThought in its metadata. */
    static GenerationResult split(ChatResponse response) {
        StringBuilder text = new StringBuilder();
        List<String> thoughts = new ArrayList<>();
        for (Generation generation : response.getResults()) {
            AssistantMessage message = generation.getOutput();
            String part = message.getText();
            if (part == null || part.isEmpty()) {
                continue;
            }
            if (Boolean.TRUE.equals(message.getMetadata().get("isThought"))) {
                thoughts.add(part.trim());
            }
            else {
                text.append(part);
            }
        }
        String joinedThoughts = String.join("\n", thoughts).trim();
        return new GenerationResult(text.toString().trim(), joinedThoughts.isEmpty() ? null : joinedThoughts);
    }
}
