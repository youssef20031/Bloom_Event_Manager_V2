package com.bloom.eventmanager.chat.pdf;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import com.bloom.eventmanager.chat.domain.ChatMessage;
import com.bloom.eventmanager.chat.domain.ChatSessionDocument;

import static org.assertj.core.api.Assertions.assertThat;

class ChatPdfRendererTest {

    private final ChatPdfRenderer renderer = new ChatPdfRenderer();

    @Test
    void rendersHeaderMessagesBulletsSourcesAndMixedScript() throws IOException {
        ChatSessionDocument session = new ChatSessionDocument();
        session.setSessionId("session_123_abcdefg");
        session.setMessages(List.of(
                new ChatMessage("user", "Plan a team day for Acme", Instant.parse("2026-09-15T10:00:00Z"), List.of(), null),
                new ChatMessage("model", "**Theme Concept**: Space Heist\n- Card game\n* Puzzle room\n1. Kickoff\nمرحبا بكم",
                        Instant.parse("2026-09-15T10:00:05Z"), List.of("guide.pdf", "brief.pdf"), "thinking")));

        byte[] pdf = renderer.render(session);

        try (PDDocument doc = Loader.loadPDF(pdf)) {
            assertThat(doc.getNumberOfPages()).isGreaterThanOrEqualTo(1);
            String text = new PDFTextStripper().getText(doc);
            assertThat(text).contains("Chat Session Log")
                    .contains("Session ID: session_123_abcdefg")
                    .contains("USER").contains("AI AGENT")
                    .contains("Theme Concept").contains("Space Heist")
                    .contains("• Card game").contains("• Puzzle room").contains("1. Kickoff")
                    .contains("Sources:").contains("[1] guide.pdf").contains("[2] brief.pdf");
        }
    }

    @Test
    void longConversationsSpillOntoMorePages() throws IOException {
        ChatSessionDocument session = new ChatSessionDocument();
        session.setSessionId("s");
        session.setMessages(java.util.stream.IntStream.range(0, 40)
                .mapToObj(i -> new ChatMessage(i % 2 == 0 ? "user" : "model", "Line " + i + " " + "words ".repeat(40),
                        Instant.now(), List.of(), null))
                .toList());

        try (PDDocument doc = Loader.loadPDF(renderer.render(session))) {
            assertThat(doc.getNumberOfPages()).isGreaterThan(1);
        }
    }
}
