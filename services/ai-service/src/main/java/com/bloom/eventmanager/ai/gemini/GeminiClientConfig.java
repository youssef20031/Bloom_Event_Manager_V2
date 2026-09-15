package com.bloom.eventmanager.ai.gemini;

import com.google.genai.Client;
import io.micrometer.observation.ObservationRegistry;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.google.genai.embedding.GoogleGenAiEmbeddingConnectionDetails;
import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingModel;
import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingOptions;
import org.springframework.ai.retry.RetryUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.bloom.eventmanager.ai.config.AiProperties;

/**
 * Manual wiring of Spring AI's Gemini models.
 *
 * <p>This is what the Spring AI starter would do for us — but the starter is
 * built against Boot 4.1.1, and Spring Cloud 2025.1.3 pins us to 4.0.8. The
 * core modules have no Boot dependency at all, so these twenty lines are the
 * whole price of staying on a supported baseline.
 */
@Configuration
public class GeminiClientConfig {

    /** Gemini Developer API mode: an API key, no Vertex project. */
    @Bean
    Client genAiClient(AiProperties properties) {
        return Client.builder().apiKey(properties.apiKey()).build();
    }

    @Bean
    ChatModel chatModel(Client client, AiProperties properties) {
        return GoogleGenAiChatModel.builder()
                .genAiClient(client)
                .options(GoogleGenAiChatOptions.builder().model(properties.model()).build())
                .retryTemplate(RetryUtils.DEFAULT_RETRY_TEMPLATE)
                .observationRegistry(ObservationRegistry.NOOP)
                .build();
    }

    @Bean
    EmbeddingModel embeddingModel(Client client, AiProperties properties) {
        GoogleGenAiEmbeddingConnectionDetails connection = GoogleGenAiEmbeddingConnectionDetails.builder()
                .apiKey(properties.apiKey())
                .genAiClient(client)
                .build();
        GoogleGenAiTextEmbeddingOptions options = GoogleGenAiTextEmbeddingOptions.builder()
                .model(properties.embeddingModel())
                .build();
        return new GoogleGenAiTextEmbeddingModel(connection, options);
    }
}
