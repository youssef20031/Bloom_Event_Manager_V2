package com.bloom.eventmanager.proposal.pdf;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import com.bloom.eventmanager.pdf.PdfFonts;
import com.bloom.eventmanager.proposal.domain.ProposalSlide;
import com.bloom.eventmanager.proposal.pdf.SlideCanvas.Align;
import com.bloom.eventmanager.proposal.pdf.SlideCanvas.Weight;

/** V1's proposalPdfService: 16:9 slides in the "neo-brutalist" palette, one renderer per slide type. */
@Component
public class ProposalPdfRenderer {

    static final Color INK = new Color(0x11, 0x11, 0x11);
    static final Color PAPER = Color.WHITE;
    static final Color PAPER_DARK = new Color(0xF4, 0xF0, 0xEC);
    static final Color ACCENT = new Color(0x25, 0x63, 0xEB);
    static final Color TEXT_SECONDARY = new Color(0x44, 0x44, 0x44);
    static final Color TEXT_MUTED = new Color(0x88, 0x88, 0x88);
    static final Color BORDER = new Color(0xDD, 0xDD, 0xDD);
    private static final Color GREY_66 = new Color(0x66, 0x66, 0x66);
    private static final Color GREY_99 = new Color(0x99, 0x99, 0x99);

    private final JsonMapper json = JsonMapper.builder().build();

    public byte[] render(List<ProposalSlide> slides, String companyName) {
        try (PDDocument doc = new PDDocument()) {
            PDDocumentInformation info = new PDDocumentInformation();
            info.setTitle(companyName + " - Event Proposal");
            info.setAuthor("Bloom Events");
            info.setSubject("Event Proposal");
            doc.setDocumentInformation(info);
            PdfFonts fonts = PdfFonts.forDocument(doc);

            for (ProposalSlide slide : slides) {
                try (SlideCanvas c = new SlideCanvas(doc, fonts)) {
                    Map<String, Object> content = slide.content() == null ? Map.of() : slide.content();
                    switch (slide.type() == null ? "" : slide.type()) {
                        case "cover" -> cover(c, slide, content);
                        case "summary" -> summary(c, slide, content);
                        case "concept" -> concept(c, slide, content);
                        case "agenda" -> agenda(c, slide, content);
                        case "activities" -> activities(c, slide, content);
                        case "venue" -> venue(c, slide, content);
                        case "catering" -> catering(c, slide, content);
                        case "investment" -> investment(c, slide, content);
                        case "closing" -> closing(c, slide, content);
                        default -> generic(c, slide, content);
                    }
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
        catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static void sectionTitle(SlideCanvas c, String title, float y) throws IOException {
        c.heading(title, 80, y, 1120, 32, INK, Align.LEFT);
        c.rect(80, y + 42, 60, 5, ACCENT);
    }

    private static void cover(SlideCanvas c, ProposalSlide slide, Map<String, Object> m) throws IOException {
        c.background(INK);
        c.rect(560, 180, 160, 8, ACCENT);
        c.heading(str(m, "companyName"), 80, 210, 1120, 52, PAPER, Align.CENTER);
        c.heading(slide.subtitle() == null ? "EVENT PROPOSAL" : slide.subtitle(), 80, 280, 1120, 18, GREY_99, Align.CENTER);
        c.text(str(m, "tagline"), 240, 340, 800, 14, Weight.ITALIC, ACCENT, Align.CENTER);
        String date = m.get("eventDate") == null ? "TBD" : str(m, "eventDate");
        String prep = m.get("preparedBy") == null ? "Bloom Events" : str(m, "preparedBy");
        c.text(date + "   •   " + prep, 80, 420, 1120, 11, Weight.REGULAR, GREY_66, Align.CENTER);
    }

    private static void summary(SlideCanvas c, ProposalSlide slide, Map<String, Object> m) throws IOException {
        c.background(PAPER);
        sectionTitle(c, slide.title(), 60);
        c.text(str(m, "overview"), 80, 120, 1000, 13, Weight.REGULAR, TEXT_SECONDARY, Align.LEFT);
        List<String> highlights = strings(m, "highlights");
        for (int i = 0; i < highlights.size(); i++) {
            float x = 80 + (i % 2) * 560;
            float y = 220 + (i / 2) * 70;
            c.rect(x, y, 540, 55, PAPER_DARK);
            c.rect(x, y, 4, 55, ACCENT);
            c.text(highlights.get(i), x + 16, y + 18, 510, 12, Weight.BOLD, INK, Align.LEFT);
        }
    }

    private static void concept(SlideCanvas c, ProposalSlide slide, Map<String, Object> m) throws IOException {
        c.background(ACCENT);
        c.heading(slide.title(), 80, 60, 1120, 16, PAPER, Align.LEFT);
        c.rect(80, 86, 60, 4, PAPER);
        c.heading(str(m, "themeName"), 80, 120, 1000, 40, PAPER, Align.LEFT);
        c.text(str(m, "description"), 80, 240, 900, 14, Weight.REGULAR, PAPER, Align.LEFT);
        if (m.get("connectionToValues") != null) {
            c.line(80, 360, 400, 360, PAPER, 2);
            c.text(str(m, "connectionToValues"), 80, 375, 900, 12, Weight.ITALIC, PAPER, Align.LEFT);
        }
    }

    private static void agenda(SlideCanvas c, ProposalSlide slide, Map<String, Object> m) throws IOException {
        c.background(PAPER);
        sectionTitle(c, slide.title(), 40);
        List<Map<String, Object>> items = maps(m, "items");
        for (int i = 0; i < Math.min(items.size(), 8); i++) {
            float y = 100 + i * 65;
            c.line(80, y + 60, 1200, y + 60, BORDER, 0.5f);
            c.text(str(items.get(i), "time"), 80, y + 8, 100, 12, Weight.MONO_BOLD, ACCENT, Align.LEFT);
            c.text(str(items.get(i), "activity"), 200, y + 5, 400, 13, Weight.BOLD, INK, Align.LEFT);
            c.text(str(items.get(i), "description"), 200, y + 24, 980, 11, Weight.REGULAR, TEXT_SECONDARY, Align.LEFT);
        }
    }

    private static void activities(SlideCanvas c, ProposalSlide slide, Map<String, Object> m) throws IOException {
        c.background(PAPER);
        sectionTitle(c, slide.title(), 40);
        List<Map<String, Object>> acts = maps(m, "activities");
        for (int i = 0; i < Math.min(acts.size(), 4); i++) {
            float x = 80 + (i % 2) * 560;
            float y = 100 + (i / 2) * 256;
            c.rect(x, y, 540, 240, PAPER_DARK);
            c.strokeRect(x, y, 540, 240, INK, 2);
            c.heading(str(acts.get(i), "name"), x + 16, y + 16, 508, 14, INK, Align.LEFT);
            c.text(str(acts.get(i), "description"), x + 16, y + 50, 508, 11, Weight.REGULAR, TEXT_SECONDARY, Align.LEFT);
            c.line(x + 16, y + 200, x + 524, y + 200, BORDER, 0.5f);
            c.text("DURATION: " + str(acts.get(i), "duration") + "     PARTICIPANTS: " + str(acts.get(i), "participants"),
                    x + 16, y + 212, 508, 9, Weight.BOLD, TEXT_MUTED, Align.LEFT);
        }
    }

    private static void venue(SlideCanvas c, ProposalSlide slide, Map<String, Object> m) throws IOException {
        c.background(INK);
        c.heading(slide.title(), 80, 60, 1120, 32, PAPER, Align.LEFT);
        c.rect(80, 102, 60, 5, ACCENT);
        block(c, 80, 130, 540, 80);
        c.text("VENUE TYPE", 96, 142, 500, 9, Weight.BOLD, ACCENT, Align.LEFT);
        c.text(m.get("venueType") == null ? "TBD" : str(m, "venueType"), 96, 160, 500, 16, Weight.BOLD, PAPER, Align.LEFT);
        block(c, 640, 130, 540, 80);
        c.text("CAPACITY", 656, 142, 500, 9, Weight.BOLD, ACCENT, Align.LEFT);
        c.text(m.get("capacity") == null ? "TBD" : str(m, "capacity"), 656, 160, 500, 16, Weight.BOLD, PAPER, Align.LEFT);
        block(c, 80, 230, 1100, 200);
        c.text("SETUP REQUIREMENTS", 96, 242, 1060, 9, Weight.BOLD, ACCENT, Align.LEFT);
        List<String> setup = strings(m, "setup");
        for (int i = 0; i < setup.size(); i++) {
            // V1 (PDFKit) used "→"; PDFBox's WinAnsiEncoding has no U+2192 glyph for
            // the Standard 14 Helvetica font and throws, so this uses the same
            // bullet character the catering slide already draws with this font.
            c.text("• " + setup.get(i), 96, 264 + i * 24, 1060, 13, Weight.REGULAR, PAPER, Align.LEFT);
        }
        if (m.get("notes") != null) {
            block(c, 80, 450, 1100, 80);
            c.text("NOTES", 96, 462, 1060, 9, Weight.BOLD, ACCENT, Align.LEFT);
            c.text(str(m, "notes"), 96, 482, 1060, 13, Weight.REGULAR, PAPER, Align.LEFT);
        }
    }

    private static void block(SlideCanvas c, float x, float y, float w, float h) throws IOException {
        c.rectWithAlpha(x, y, w, h, PAPER, 0.05f);
        c.strokeRect(x, y, w, h, new Color(0x40, 0x40, 0x40), 1);
    }

    private static void catering(SlideCanvas c, ProposalSlide slide, Map<String, Object> m) throws IOException {
        c.background(PAPER);
        sectionTitle(c, slide.title(), 40);
        List<Map<String, Object>> meals = maps(m, "meals");
        for (int i = 0; i < meals.size(); i++) {
            float y = 110 + i * 60;
            if (y > 500) {
                break;
            }
            c.rect(80, y, 700, 50, PAPER_DARK);
            c.rect(80, y, 4, 50, ACCENT);
            c.text(str(meals.get(i), "name"), 96, y + 8, 680, 13, Weight.BOLD, INK, Align.LEFT);
            c.text(str(meals.get(i), "description"), 96, y + 28, 680, 11, Weight.REGULAR, TEXT_SECONDARY, Align.LEFT);
        }
        c.rect(820, 110, 380, 120, PAPER_DARK);
        c.strokeRect(820, 110, 380, 120, INK, 2);
        c.text("DIETARY NOTES", 836, 122, 348, 9, Weight.BOLD, ACCENT, Align.LEFT);
        c.text(m.get("dietaryNotes") == null ? "None specified" : str(m, "dietaryNotes"), 836, 142, 348, 11, Weight.REGULAR, INK, Align.LEFT);
        List<String> amenities = strings(m, "amenities");
        if (!amenities.isEmpty()) {
            c.text("AMENITIES", 820, 250, 380, 9, Weight.BOLD, ACCENT, Align.LEFT);
            for (int i = 0; i < amenities.size(); i++) {
                c.text("• " + amenities.get(i), 820, 270 + i * 20, 380, 11, Weight.BOLD, INK, Align.LEFT);
            }
        }
    }

    private static void investment(SlideCanvas c, ProposalSlide slide, Map<String, Object> m) throws IOException {
        c.background(PAPER);
        sectionTitle(c, slide.title(), 40);
        c.text("CATEGORY", 80, 110, 200, 10, Weight.BOLD, TEXT_MUTED, Align.LEFT);
        c.text("DESCRIPTION", 300, 110, 600, 10, Weight.BOLD, TEXT_MUTED, Align.LEFT);
        c.text("TIER", 1050, 110, 130, 10, Weight.BOLD, TEXT_MUTED, Align.LEFT);
        c.line(80, 130, 1200, 130, INK, 2);
        List<Map<String, Object>> categories = maps(m, "categories");
        for (int i = 0; i < categories.size(); i++) {
            float y = 145 + i * 50;
            if (y > 580) {
                break;
            }
            c.line(80, y + 42, 1200, y + 42, BORDER, 0.5f);
            c.text(str(categories.get(i), "category"), 80, y + 8, 200, 13, Weight.BOLD, INK, Align.LEFT);
            c.text(str(categories.get(i), "description"), 300, y + 10, 720, 11, Weight.REGULAR, TEXT_SECONDARY, Align.LEFT);
            String tier = categories.get(i).get("tier") == null ? "Standard" : str(categories.get(i), "tier");
            c.rect(1050, y + 4, 120, 26, ACCENT);
            c.text(tier.toUpperCase(), 1050, y + 12, 120, 9, Weight.BOLD, PAPER, Align.CENTER);
        }
        if (m.get("note") != null) {
            c.text(str(m, "note"), 80, 620, 1100, 11, Weight.REGULAR, TEXT_MUTED, Align.LEFT);
        }
    }

    private static void closing(SlideCanvas c, ProposalSlide slide, Map<String, Object> m) throws IOException {
        c.background(INK);
        c.rect(560, 140, 160, 8, ACCENT);
        c.heading(slide.title(), 80, 170, 1120, 32, PAPER, Align.CENTER);
        c.text(str(m, "message"), 200, 240, 880, 16, Weight.REGULAR, PAPER, Align.CENTER);
        List<String> values = strings(m, "valueProps");
        float totalW = values.size() * 220 + Math.max(0, values.size() - 1) * 20;
        float startX = (SlideCanvas.W - totalW) / 2;
        for (int i = 0; i < values.size(); i++) {
            float x = startX + i * 240;
            c.strokeRect(x, 370, 220, 40, ACCENT, 2);
            c.heading(values.get(i), x, 383, 220, 10, PAPER, Align.CENTER);
        }
        if (m.get("contactInfo") != null) {
            c.text(str(m, "contactInfo"), 80, 460, 1120, 14, Weight.ITALIC, ACCENT, Align.CENTER);
        }
    }

    private void generic(SlideCanvas c, ProposalSlide slide, Map<String, Object> m) throws IOException {
        c.background(PAPER);
        sectionTitle(c, slide.title() == null ? "" : slide.title(), 60);
        String dump = json.writerWithDefaultPrettyPrinter().writeValueAsString(m);
        float y = 130;
        // The pretty-printer emits the platform line separator (\r\n on
        // Windows); splitting on \R rather than \n keeps a trailing \r off
        // the end of each line, which PDFBox's WinAnsiEncoding cannot encode
        // as a drawable glyph. BriefPdfRenderer strips \r for the same reason.
        for (String line : dump.split("\\R")) {
            c.text(line, 80, y, 1100, 12, Weight.MONO, TEXT_SECONDARY, Align.LEFT);
            y += 16;
        }
    }

    private static String str(Map<String, Object> m, String key) {
        Object value = m.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private static List<String> strings(Map<String, Object> m, String key) {
        Object value = m.get(key);
        return value instanceof List<?> list ? list.stream().map(String::valueOf).toList() : List.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> maps(Map<String, Object> m, String key) {
        Object value = m.get(key);
        return value instanceof List<?> list ? (List<Map<String, Object>>) (List<?>) list.stream().filter(Map.class::isInstance).toList() : List.of();
    }
}
