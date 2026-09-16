package com.bloom.eventmanager.proposal.pdf;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.springframework.stereotype.Component;

import com.bloom.eventmanager.pdf.PdfFonts;
import com.bloom.eventmanager.proposal.domain.EventIntakeData;

/** V1's SubmitProposalUseCase.generatePdfBuffer: the "Event Intake & Customer Brief" A4 document. */
@Component
public class BriefPdfRenderer {

    static final Map<String, String> DURATION = Map.of(
            "half-day", "Half-Day (approx. 3-4 hours)",
            "full-day", "Full-Day (approx. 6-8 hours, including breaks)",
            "multi-day", "Multi-Day Retreat");
    static final Map<String, String> ENERGY = Map.of(
            "high-energy", "High-energy, fast-paced, and competitive",
            "relaxed", "Relaxed, mindful, and conversational",
            "balanced", "A balanced mix of high-energy activities and focused downtime");
    static final Map<String, String> VENUE = Map.of(
            "indoor", "Indoor Venue", "outdoor", "Outdoor Venue", "hybrid", "Hybrid (Indoor + Outdoor)");
    static final Map<String, String> YES_NO = Map.of(
            "yes", "Yes", "no", "No", "not-sure", "Not Sure / To Be Determined");
    static final Map<String, String> LUNCH = Map.of(
            "30min", "30 minutes", "45min", "45 minutes", "1hour", "1 hour", "1.5hours", "1.5 hours",
            "flexible", "Flexible / To Be Determined");

    private static final Color PRIMARY = new Color(0x4F, 0x46, 0xE5);
    private static final Color SECONDARY = new Color(0x11, 0x18, 0x27);
    private static final Color TEXT = new Color(0x37, 0x41, 0x51);
    private static final Color LIGHT = new Color(0x6B, 0x72, 0x80);
    private static final Color ACCENT = new Color(0xEE, 0xF2, 0xFF);
    private static final Color BORDER = new Color(0xE5, 0xE7, 0xEB);
    private static final float PAGE_W = PDRectangle.A4.getWidth();
    private static final float PAGE_H = PDRectangle.A4.getHeight();
    private static final float LEFT = 50f;
    private static final float RIGHT = PAGE_W - 50f;
    private static final float BOTTOM_MARGIN = 80f;
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("MMMM d, yyyy 'at' hh:mm a");

    public byte[] render(EventIntakeData d) {
        try (PDDocument doc = new PDDocument()) {
            PdfFonts fonts = PdfFonts.forDocument(doc);
            Writer w = new Writer(doc, fonts);

            w.header(d.companyName() == null || d.companyName().isBlank() ? "Client Brief" : d.companyName());

            w.section("01", "Client & Event Information");
            w.field("Contact Person", d.contactPerson());
            w.field("Contact Method", d.contactEmailPhone());
            w.field("Target Dates", d.targetEventDates());
            w.field("Industry / Type", d.customerType());

            w.section("02", "Core Theme & Objectives");
            w.checklist("Primary Goals", d.primaryGoals(), d.otherGoal());
            w.field("Core Values / Themes", d.coreValuesThemes());
            w.field("Company Culture", d.companyCulture());
            w.checklist("Culture Attributes", d.cultureAttributes(), null);
            w.field("Negative Attributes", d.negativeAttributes());
            w.field("Culture Measurement", d.measureCulture());
            w.field("Government Involved?", "yes".equals(d.governmentInvolvement()) ? "Yes" : "No");

            w.section("03", "Event Scope & Logistics");
            w.field("Expected Participants", d.expectedParticipants());
            w.checklist("Age Groups", d.ageGroup(), null);
            w.field("Duration", label(DURATION, d.eventDuration()));
            w.field("Energy & Pacing", label(ENERGY, d.energyPacing()));

            w.section("04", "Venue & Facilities");
            w.field("Venue Type", label(VENUE, d.venueType()));
            w.field("Facilities", "Theatre / Stage: " + orDash(label(YES_NO, d.hasTheatre()))
                    + "\nSound System: " + orDash(label(YES_NO, d.hasSoundSystem())));
            w.field("Location", d.facilityLocation());
            w.field("Notes", d.venueNotes());

            w.section("05", "Cultural Considerations");
            w.checklist("Diversity", d.culturalDiversity(), null);
            w.field("Primary Languages", d.primaryLanguages());
            w.checklist("Dietary/Religious", d.dietaryRestrictions(), null);
            w.field("Regional Distribution", d.regionalDistribution());
            w.field("Cultural Sensitivities", d.culturalSensitivities());
            w.checklist("Specific Sensitivities", d.cultureSensitivities(), null);

            w.section("06", "Participant & Interaction Goals");
            w.checklist("Participant Types", d.participantTypes(), null);
            w.checklist("Interaction Goals", d.interactionGoals(), d.otherInteractionGoal());
            w.field("Audience Experience Goal", d.audienceExperience());

            w.section("07", "Activity Style & Tone");
            w.checklist("Ideal Feel", d.activityFeel(), null);
            w.field("Activities to Avoid", d.activitiesToAvoid());

            w.section("08", "Food & Amenities");
            w.checklist("Catering", d.cateringOptions(), null);
            w.checklist("Detailed Catering", d.cateringDetails(), null);
            w.field("Lunch Break", label(LUNCH, d.lunchBreak()));
            w.field("Food Booths", label(YES_NO, d.foodBooths()));
            String giveaways = orDash(label(YES_NO, d.giveaways()));
            if ("yes".equals(d.giveaways()) && d.giveawaysDetails() != null && !d.giveawaysDetails().isBlank()) {
                giveaways += " (" + d.giveawaysDetails() + ")";
            }
            w.field("Giveaways", giveaways);

            w.section("09", "Additional Context");
            w.field("Critical Context / Budget", d.additionalContext());

            w.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
        catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static String label(Map<String, String> labels, String key) {
        return key == null || key.isBlank() ? null : labels.getOrDefault(key, key);
    }

    private static String orDash(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }

    /** Sequential writer with page breaks; footers are drawn once every page exists so "of N" is known. */
    private static final class Writer {

        private final PDDocument doc;
        private final PdfFonts fonts;
        private final List<PDPageContentStream> streams = new ArrayList<>();
        private PDPageContentStream content;
        private float y;

        Writer(PDDocument doc, PdfFonts fonts) throws IOException {
            this.doc = doc;
            this.fonts = fonts;
            newPage();
        }

        private void newPage() throws IOException {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            content = new PDPageContentStream(doc, page);
            streams.add(content);
            y = 50;
        }

        private void need(float space) throws IOException {
            if (y + space > PAGE_H - BOTTOM_MARGIN) {
                newPage();
            }
        }

        void header(String company) throws IOException {
            fill(PRIMARY, 0, 0, PAGE_W, 140);
            text(fonts.bold(), 28, Color.WHITE, company, 120, 45);
            text(fonts.regular(), 14, Color.WHITE, "Event Intake & Customer Brief", 120, 80);
            String date = STAMP.format(ZonedDateTime.now(ZoneId.systemDefault()));
            float dateW = PdfFonts.width(fonts.bold(), date, 10) + 20;
            fill(new Color(0x7C, 0x74, 0xEB), PAGE_W - 50 - dateW, 45, dateW, 24);
            text(fonts.bold(), 10, Color.WHITE, date, PAGE_W - 50 - dateW + 10, 52);
            y = 170;
        }

        void section(String number, String title) throws IOException {
            need(80);
            y += 18;
            fill(ACCENT, 50, y, 30, 20);
            text(fonts.bold(), 10, PRIMARY, number, 50 + (30 - PdfFonts.width(fonts.bold(), number, 10)) / 2, y + 5);
            text(fonts.bold(), 16, SECONDARY, title, 90, y + 2);
            line(50, y + 35, RIGHT, y + 35, BORDER);
            y += 50;
        }

        void field(String label, String value) throws IOException {
            need(40);
            text(fonts.bold(), 10, LIGHT, label.toUpperCase(), 50, y);
            y += 16;
            for (String line : wrap(orDash(value), fonts.regular(), 11, 480)) {
                need(16);
                text(fonts.regular(), 11, TEXT, line, 50, y);
                y += 15;
            }
            y += 10;
        }

        void checklist(String label, List<String> items, String other) throws IOException {
            need(50);
            text(fonts.bold(), 10, LIGHT, label.toUpperCase(), 50, y);
            y += 16;
            boolean empty = (items == null || items.isEmpty()) && (other == null || other.isBlank());
            if (empty) {
                text(fonts.italic(), 11, LIGHT, "None selected", 50, y);
                y += 15;
            }
            else {
                if (items != null) {
                    for (String item : items) {
                        bullet(item);
                    }
                }
                if (other != null && !other.isBlank()) {
                    bullet("Other: " + other);
                }
            }
            y += 10;
        }

        private void bullet(String item) throws IOException {
            need(25);
            content.setNonStrokingColor(PRIMARY);
            float cx = 55, cy = PAGE_H - (y + 6);
            content.moveTo(cx + 2, cy);
            content.curveTo(cx + 2, cy + 1.1f, cx + 1.1f, cy + 2, cx, cy + 2);
            content.curveTo(cx - 1.1f, cy + 2, cx - 2, cy + 1.1f, cx - 2, cy);
            content.curveTo(cx - 2, cy - 1.1f, cx - 1.1f, cy - 2, cx, cy - 2);
            content.curveTo(cx + 1.1f, cy - 2, cx + 2, cy - 1.1f, cx + 2, cy);
            content.fill();
            text(fonts.regular(), 11, TEXT, item, 70, y);
            y += 15;
        }

        void finish() throws IOException {
            int total = streams.size();
            for (int i = 0; i < total; i++) {
                PDPageContentStream s = streams.get(i);
                float footerY = PAGE_H - 40;
                s.setStrokingColor(BORDER);
                s.setLineWidth(1);
                s.moveTo(50, footerY + 10);
                s.lineTo(RIGHT, footerY + 10);
                s.stroke();
                drawText(s, fonts.regular(), 8, LIGHT, "Generated by Bloom RAG System", 50, footerY);
                String pageLabel = "Page " + (i + 1) + " of " + total;
                drawText(s, fonts.regular(), 8, LIGHT, pageLabel, RIGHT - PdfFonts.width(fonts.regular(), pageLabel, 8), footerY);
                s.close();
            }
        }

        private void fill(Color color, float x, float yFromTop, float w, float h) throws IOException {
            content.setNonStrokingColor(color);
            content.addRect(x, PAGE_H - yFromTop - h, w, h);
            content.fill();
        }

        private void line(float x1, float y1, float x2, float y2, Color color) throws IOException {
            content.setStrokingColor(color);
            content.setLineWidth(1);
            content.moveTo(x1, PAGE_H - y1);
            content.lineTo(x2, PAGE_H - y2);
            content.stroke();
        }

        private void text(PDFont font, float size, Color color, String text, float x, float yFromTop) throws IOException {
            drawText(content, font, size, color, text, x, PAGE_H - yFromTop - size);
        }

        private static void drawText(PDPageContentStream s, PDFont font, float size, Color color, String text, float x, float baseline) throws IOException {
            s.setNonStrokingColor(color);
            s.beginText();
            s.setFont(font, size);
            s.newLineAtOffset(x, baseline);
            s.showText(text.replace("\r", ""));
            s.endText();
        }

        private static List<String> wrap(String value, PDFont font, float size, float width) {
            List<String> lines = new ArrayList<>();
            for (String paragraph : value.split("\n")) {
                StringBuilder current = new StringBuilder();
                for (String word : paragraph.split(" ")) {
                    String candidate = current.isEmpty() ? word : current + " " + word;
                    if (PdfFonts.width(font, candidate, size) > width && !current.isEmpty()) {
                        lines.add(current.toString());
                        current = new StringBuilder(word);
                    }
                    else {
                        current = new StringBuilder(candidate);
                    }
                }
                lines.add(current.toString());
            }
            return lines;
        }
    }
}
