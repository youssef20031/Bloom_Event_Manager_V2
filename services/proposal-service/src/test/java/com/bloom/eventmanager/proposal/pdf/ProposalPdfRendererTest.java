package com.bloom.eventmanager.proposal.pdf;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import com.bloom.eventmanager.proposal.domain.ProposalSlide;

import static org.assertj.core.api.Assertions.assertThat;

class ProposalPdfRendererTest {

    private final ProposalPdfRenderer renderer = new ProposalPdfRenderer();

    @Test
    void rendersOneLandscapePagePerSlideWithTheirContent() throws IOException {
        List<ProposalSlide> slides = List.of(
                new ProposalSlide(1, "cover", "Acme", "Event Proposal", Map.of("companyName", "Acme", "eventDate", "June 2026", "tagline", "Play together", "preparedBy", "Bloom Events")),
                new ProposalSlide(2, "summary", "Executive Summary", null, Map.of("overview", "A day of puzzles", "highlights", List.of("Heist theme", "40 people"))),
                new ProposalSlide(3, "concept", "Event Concept & Theme", null, Map.of("themeName", "Space Heist", "description", "Crack the vault", "connectionToValues", "Curiosity")),
                new ProposalSlide(4, "agenda", "Proposed Agenda", null, Map.of("items", List.of(Map.of("time", "9:00 AM", "activity", "Kickoff", "description", "Welcome")))),
                new ProposalSlide(5, "activities", "Teambuilding Activities", null, Map.of("activities", List.of(Map.of("name", "Candy Love", "description", "Sweet trading", "duration", "45 min", "participants", "Teams of 5")))),
                new ProposalSlide(6, "venue", "Venue & Logistics", null, Map.of("venueType", "Indoor", "capacity", "60", "setup", List.of("Round tables"), "notes", "Near parking")),
                new ProposalSlide(7, "catering", "Catering & Amenities", null, Map.of("meals", List.of(Map.of("name", "Lunch", "description", "Buffet")), "dietaryNotes", "Halal", "amenities", List.of("Coffee"))),
                new ProposalSlide(8, "investment", "Investment Summary", null, Map.of("categories", List.of(Map.of("category", "Venue", "description", "Hall hire", "tier", "Premium")), "note", "Estimates")),
                new ProposalSlide(9, "closing", "Why Bloom", null, Map.of("message", "We make it fun", "valueProps", List.of("Creative", "Reliable"), "contactInfo", "Let's create something extraordinary together.")),
                new ProposalSlide(10, "mystery", "Unknown Type", null, Map.of("k", "v")));

        byte[] pdf = renderer.render(slides, "Acme");

        try (PDDocument doc = Loader.loadPDF(pdf)) {
            assertThat(doc.getNumberOfPages()).isEqualTo(10);
            assertThat(doc.getPage(0).getMediaBox().getWidth()).isEqualTo(1280f);
            assertThat(doc.getPage(0).getMediaBox().getHeight()).isEqualTo(720f);
            assertThat(doc.getDocumentInformation().getTitle()).isEqualTo("Acme - Event Proposal");
            String text = new PDFTextStripper().getText(doc);
            assertThat(text).contains("ACME").contains("EVENT PROPOSAL").contains("Play together")
                    .contains("EXECUTIVE SUMMARY").contains("Heist theme")
                    .contains("SPACE HEIST").contains("9:00 AM").contains("Kickoff")
                    .contains("CANDY LOVE").contains("DURATION: 45 min")
                    .contains("VENUE TYPE").contains("Round tables")
                    .contains("DIETARY NOTES").contains("Halal")
                    .contains("PREMIUM").contains("WHY BLOOM").contains("CREATIVE")
                    .contains("\"k\"");
        }
    }

    @Test
    void arabicTitlesRenderWithoutError() throws IOException {
        byte[] pdf = renderer.render(List.of(
                new ProposalSlide(1, "concept", "مفهوم الفعالية", null, Map.of("themeName", "ليلة الألغاز", "description", "وصف", "connectionToValues", "قيم"))), "شركة");

        try (PDDocument doc = Loader.loadPDF(pdf)) {
            assertThat(doc.getNumberOfPages()).isEqualTo(1);
        }
    }
}
