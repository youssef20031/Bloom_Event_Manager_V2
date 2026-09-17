package com.bloom.eventmanager.chat.pdf;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
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
                    .contains("Sources:").contains("[1] guide.pdf").contains("[2] brief.pdf")
                    // PDFBox's PDFTextStripper un-shapes and un-reorders extracted Arabic back to
                    // its original logical form, so the drawn phrase round-trips to this exact
                    // string if (and only if) MixedTextWriter shaped it correctly.
                    .contains("مرحبا بكم");
        }
    }

    /**
     * Regression guard: sources are Google Drive filenames, drawn through the
     * one showText() in this class that was never routed through SafeText.
     * A WinAnsi-incompatible character in a filename — an Arabic name is
     * realistic input from a Cairo-based business — made PDFont.encode throw
     * IllegalArgumentException, which render()'s catch (IOException) does not
     * catch; it escaped as an opaque 500. V1 (PDFKit) never throws on an
     * unsupported glyph, so the parity target is "renders, with placeholders".
     */
    @Test
    void arabicSourceFilenameDegradesToPlaceholdersRatherThanThrowing() throws IOException {
        ChatSessionDocument session = new ChatSessionDocument();
        session.setSessionId("s");
        session.setMessages(List.of(new ChatMessage("model", "Here is the guide.", Instant.parse("2026-09-15T10:00:00Z"),
                List.of("دليل الفعاليات.pdf"), null)));

        byte[] pdf = renderer.render(session);

        try (PDDocument doc = Loader.loadPDF(pdf)) {
            String text = new PDFTextStripper().getText(doc);
            assertThat(text).contains("Sources:").contains("[1]").contains(".pdf");
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

    /**
     * Regression guard: mixedLine() used to reserve room for exactly one
     * physical line ({@code ensure(18)}) even though a single logical line
     * can wrap into several. A long, unbroken paragraph landing in roughly
     * the bottom 70pt of a page would draw its trailing wrapped lines below
     * the page's bottom margin (or entirely off the page) before the
     * overflow was ever noticed — {@code getNumberOfPages() > 1} cannot
     * catch this, since PDFTextStripper extracts text regardless of where on
     * (or off) the page it was actually drawn. Many long, unbroken
     * paragraphs guarantee at least one lands in that danger zone on some
     * page, so this walks every extracted glyph's real baseline position
     * (recovered from PDFBox's top-down TextPosition space via
     * {@code pageHeight - getY()}, confirmed empirically) and asserts none
     * of them fall outside the page's margins.
     */
    @Test
    void wrappedParagraphsNeverDrawOutsideThePageMargins() throws IOException {
        ChatSessionDocument session = new ChatSessionDocument();
        session.setSessionId("s");
        session.setMessages(java.util.stream.IntStream.range(0, 30)
                .mapToObj(i -> new ChatMessage(i % 2 == 0 ? "user" : "model",
                        "Paragraph " + i + " " + "word ".repeat(60), Instant.now(), List.of(), null))
                .toList());

        byte[] pdf = renderer.render(session);

        float pageHeight = PDRectangle.LETTER.getHeight();
        float margin = 50f; // matches ChatPdfRenderer's private MARGIN
        List<Float> baselines = new ArrayList<>();
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            PDFTextStripper stripper = new PDFTextStripper() {
                @Override
                protected void writeString(String string, List<TextPosition> textPositions) throws IOException {
                    for (TextPosition position : textPositions) {
                        baselines.add(pageHeight - position.getY());
                    }
                }
            };
            stripper.getText(doc);
        }

        assertThat(baselines).isNotEmpty();
        assertThat(baselines).as("every glyph's baseline must stay within the page's usable area")
                .allSatisfy(baseline -> assertThat(baseline).isBetween(margin - 0.5f, pageHeight - margin + 0.5f));
    }
}
