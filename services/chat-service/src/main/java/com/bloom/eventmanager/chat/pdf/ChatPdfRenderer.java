package com.bloom.eventmanager.chat.pdf;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.springframework.stereotype.Component;

import com.bloom.eventmanager.chat.domain.ChatMessage;
import com.bloom.eventmanager.chat.domain.ChatSessionDocument;
import com.bloom.eventmanager.pdf.MixedTextWriter;
import com.bloom.eventmanager.pdf.PdfFonts;
import com.bloom.eventmanager.pdf.SafeText;
import com.bloom.eventmanager.pdf.ScriptRuns;

/**
 * V1's generateChatPdf, with bloom-pdf doing the Arabic work. Letter pages,
 * 50pt margins, role header on a grey box, bullets and numbered lines indented
 * 15pt, timestamp right-aligned in grey, then a numbered "Sources:" list.
 */
@Component
public class ChatPdfRenderer {

    private static final float MARGIN = 50f;
    private static final Color USER = new Color(0x25, 0x63, 0xEB);
    private static final Color AI = new Color(0x05, 0x96, 0x69);
    private static final Color BOX = new Color(0xF3, 0xF4, 0xF6);
    private static final Color RULE = new Color(0xE5, 0xE7, 0xEB);
    private static final Color MUTED = new Color(0x6B, 0x72, 0x80);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    public byte[] render(ChatSessionDocument session) {
        try (PDDocument doc = new PDDocument()) {
            PdfFonts fonts = PdfFonts.forDocument(doc);
            Page page = new Page(doc, fonts);

            page.centered(fonts.bold(), 20, "Chat Session Log");
            page.y -= 26;
            page.centered(fonts.regular(), 10, "Session ID: " + session.getSessionId());
            page.y -= 16;
            page.centered(fonts.regular(), 10, "Generated: " + STAMP.format(java.time.Instant.now()));
            page.y -= 36;

            List<ChatMessage> messages = session.getMessages();
            for (int i = 0; i < messages.size(); i++) {
                ChatMessage message = messages.get(i);
                if (i > 0) {
                    page.ensure(40);
                    page.y -= 18;
                    page.rule();
                    page.y -= 18;
                }
                boolean user = "user".equals(message.role());
                page.roleHeader(user ? "USER" : "AI AGENT", user ? USER : AI);

                for (String line : message.content().split("\n")) {
                    if (line.isBlank()) {
                        page.y -= 6;
                        continue;
                    }
                    String text = line;
                    float indent = 0;
                    String trimmed = line.trim();
                    if (trimmed.startsWith("- ") || trimmed.startsWith("* ")) {
                        text = "• " + trimmed.substring(2);
                        indent = 15;
                    }
                    else if (trimmed.matches("^\\d+\\.\\s.*")) {
                        text = trimmed;
                        indent = 15;
                    }
                    page.mixedLine(text, indent);
                    page.y -= 3;
                }

                if (message.timestamp() != null) {
                    page.ensure(14);
                    page.y -= 6;
                    page.rightAligned(fonts.regular(), 8, MUTED, STAMP.format(message.timestamp()));
                    page.y -= 10;
                }

                if (message.sources() != null && !message.sources().isEmpty()) {
                    page.ensure(20);
                    page.y -= 10;
                    page.text(fonts.bold(), 9, new Color(0x4B, 0x55, 0x63), "Sources:", MARGIN);
                    page.y -= 12;
                    for (int s = 0; s < message.sources().size(); s++) {
                        page.ensure(12);
                        page.text(fonts.regular(), 8, MUTED, "[" + (s + 1) + "] " + message.sources().get(s), MARGIN + 15);
                        page.y -= 11;
                    }
                }
            }

            page.close();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
        catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /** Cursor-based page writer with automatic page breaks. */
    private static final class Page {

        private final PDDocument doc;
        private final PdfFonts fonts;
        private PDPageContentStream content;
        private float y;
        private final float width = PDRectangle.LETTER.getWidth();

        Page(PDDocument doc, PdfFonts fonts) throws IOException {
            this.doc = doc;
            this.fonts = fonts;
            newPage();
        }

        private void newPage() throws IOException {
            if (content != null) {
                content.close();
            }
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            content = new PDPageContentStream(doc, page);
            y = PDRectangle.LETTER.getHeight() - MARGIN;
        }

        void ensure(float needed) throws IOException {
            if (y - needed < MARGIN) {
                newPage();
            }
        }

        void centered(PDFont font, float size, String text) throws IOException {
            ensure(size + 4);
            float x = (width - PdfFonts.width(font, text, size)) / 2;
            draw(font, size, Color.BLACK, text, x, y);
        }

        void rightAligned(PDFont font, float size, Color color, String text) throws IOException {
            ensure(size + 4);
            draw(font, size, color, text, width - MARGIN - PdfFonts.width(font, text, size), y);
        }

        void text(PDFont font, float size, Color color, String text, float x) throws IOException {
            ensure(size + 4);
            draw(font, size, color, text, x, y);
        }

        void roleHeader(String role, Color color) throws IOException {
            ensure(30);
            float w = PdfFonts.width(fonts.bold(), role, 10) + 10;
            content.setNonStrokingColor(BOX);
            content.setStrokingColor(RULE);
            content.addRect(MARGIN, y - 12, w, 16);
            content.fillAndStroke();
            draw(fonts.bold(), 10, color, role, MARGIN + 5, y - 8);
            y -= 26;
        }

        void mixedLine(String text, float indent) throws IOException {
            List<ScriptRuns.Run> runs = ScriptRuns.split(text);
            float x = MARGIN + indent;
            float maxWidth = width - 2 * MARGIN - indent;
            // A logical line can wrap into any number of physical lines, so a
            // fixed "ensure(18)" (room for one line) let long paragraphs draw
            // past the bottom margin before the overflow was ever noticed.
            // Measuring first — with the same wrapping MixedTextWriter is
            // about to use — tells ensure() the real height up front.
            ensure(MixedTextWriter.measure(runs, x, maxWidth, 12, 11, fonts));
            content.setNonStrokingColor(Color.BLACK);
            y = MixedTextWriter.writeLine(content, runs, x, y, maxWidth, 12, 11, fonts);
        }

        void rule() throws IOException {
            content.setStrokingColor(RULE);
            content.setLineWidth(1);
            content.moveTo(MARGIN, y);
            content.lineTo(width - MARGIN, y);
            content.stroke();
        }

        private void draw(PDFont font, float size, Color color, String text, float x, float baseline) throws IOException {
            content.setNonStrokingColor(color);
            content.beginText();
            content.setFont(font, size);
            content.newLineAtOffset(x, baseline);
            // Drive filenames reach this via the "Sources:" list — an Arabic,
            // emoji, or other WinAnsi-incompatible character in one throws
            // IllegalArgumentException from PDFont.encode otherwise, and V1
            // (PDFKit) never throws on an unsupported glyph.
            content.showText(SafeText.forFont(font, text));
            content.endText();
        }

        void close() throws IOException {
            content.close();
        }
    }
}
