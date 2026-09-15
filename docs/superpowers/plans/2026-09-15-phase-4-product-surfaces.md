# Phase 4 — Product Surfaces: Chat and Proposals

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver the two user-facing services — `chat-service` (Event Advisor with history, Arabic-aware PDF export, Drive backup) and `proposal-service` (companies, AI proposal generation, proposal PDF, event-intake brief) — reaching full feature parity with V1.

**Architecture:** Both services are compositions over Phases 1–3: retrieval for context, `ai-service` for generation, `knowledge-service` for company sources, `storage-service` for every Drive interaction. `chat-service` owns Mongo `chatdb` in V1's Mongoose document shape. PDF work lives in a new plain library `bloom-pdf` that replaces V1's hand-rolled Arabic reshaping with ICU4J's `ArabicShaping` + `Bidi`, used by both services. No new Kafka topics.

**Tech Stack:** Spring Boot 4.0.8, Spring Cloud 2025.1.3 (OpenFeign, Eureka), Spring Data MongoDB, Apache PDFBox 3.0.8, ICU4J 78.3, Caffeine, Jackson 3, Testcontainers 2.0.5, JUnit 5, Mockito.

**Spec:** `docs/superpowers/specs/2026-08-30-bloom-spring-microservices-design.md` (sections 5.2 chat/proposal, 8 "The Arabic PDF path improves", 13)

**Prerequisite:** Phase 3b complete — Drive authenticated, `storage-service` exposing `/companies`, `/company-files`, `/chat-backups`.

## Global Constraints

- **Spring Boot `4.0.8`, Spring Cloud `2025.1.3`, Java `25`, group `com.bloom.eventmanager`, version `0.0.1-SNAPSHOT`.**
- **Boot 4.0 test modules:** `spring-boot-webmvc-test` (`org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest`), `@MockitoBean` (`org.springframework.test.context.bean.override.mockito`), `spring-boot-testcontainers` (`@ServiceConnection`).
- **Testcontainers 2.0.5:** `org.testcontainers:testcontainers-mongodb` + `testcontainers-junit-jupiter`; class `org.testcontainers.mongodb.MongoDBContainer("mongo:8.0")` — the class Boot 4.0.8's factory binds. Integration tests are `*IntegrationTests` with `@Testcontainers(disabledWithoutDocker = true)`.
- **Every `@SpringBootTest` sets** `spring.config.import=`, `eureka.client.enabled=false`, `spring.cloud.discovery.enabled=false`.
- **JSON:** parse with `tools.jackson.databind.json.JsonMapper` (Jackson 3); annotations from `com.fasterxml.jackson.annotation`.
- **Mongo collection names are V1's Mongoose names:** `chatsessions`. Database `${MONGO_CHAT_DB:chatdb}`; set `MONGO_CHAT_DB` to V1's database to read existing sessions.
- **Prompts are V1's, verbatim,** as Java text blocks. Do not "improve" wording.
- **Binary content to `storage-service` travels as Base64 in JSON** (`contentBase64`), matching Phase 3b's endpoints.
- **Arabic rendering uses ICU4J only.** `ArabicShaping(LETTERS_SHAPE)` for glyph joining, `Bidi.setPara` + `writeReordered(DO_MIRRORING)` for reordering. V1's manual `reverseString` is gone — Bidi does the reordering correctly.
- **Amiri fonts** are copied from `E:\Bloom_Event_Manager\src\server\assets\fonts\Amiri-Regular.ttf` and `Amiri-Bold.ttf` into `platform/bloom-pdf/src/main/resources/fonts/` (SIL Open Font License; ~0.8 MB total, committed).
- **`storage-service` 401s (Drive not authenticated) are re-raised with V1's shape** — `errorCode: DRIVE_NOT_AUTHENTICATED`, `authUrl: /api/v1/rag/sync/auth?force=true` — so the React app's re-auth handling keeps working.
- **Gateway property prefix `spring.cloud.gateway.server.webflux.*`; RewritePath uses `$\{rest}`** to escape Spring placeholders.
- **Commit with explicit paths, never `git add -A`.**

---

## File Structure

```
platform/bloom-bom/pom.xml                                MODIFY: bloom-pdf pin
platform/bloom-pdf/
  pom.xml
  src/main/resources/fonts/Amiri-Regular.ttf, Amiri-Bold.ttf   copied from V1
  src/main/java/com/bloom/eventmanager/pdf/
    ArabicText.java            shape + reorder + isArabic
    ScriptRuns.java            **bold** + script segmentation -> List<Run>
    PdfFonts.java              Amiri (Type0) + Standard 14 fonts per document
    MixedTextWriter.java       wrapped, mixed-script line drawing
  src/test/java/com/bloom/eventmanager/pdf/
    ArabicTextTest.java, ScriptRunsTest.java, MixedTextWriterTest.java

services/chat-service/
  pom.xml, src/main/resources/application.yml
  .../chat/ChatServiceApplication.java
  .../chat/config/ChatProperties.java
  .../chat/client/RetrievalClient.java, AiClient.java, StorageClient.java (+ dto)
  .../chat/domain/ChatMessage.java, ChatSessionDocument.java, ChatSessionRepository.java,
                  ChatSessionStore.java, ChatWithDocumentsUseCase.java, ChatResult.java
  .../chat/pdf/ChatPdfRenderer.java
  .../chat/api/ChatController.java, SessionController.java (+ dto)
  test: domain/ChatSessionStoreIntegrationTests, domain/ChatWithDocumentsUseCaseTest,
        pdf/ChatPdfRendererTest, api/ChatControllerTest, api/SessionControllerTest
config-repo/chat-service.yml

services/proposal-service/
  pom.xml, src/main/resources/application.yml
  .../proposal/ProposalServiceApplication.java
  .../proposal/client/KnowledgeClient.java, AiClient.java, ChatClient.java, StorageClient.java (+ dto)
  .../proposal/domain/ProposalSlide.java, GeneratedProposal.java, GenerateProposalUseCase.java,
                      EventIntakeData.java, IntakeSubmission.java, SubmitIntakeUseCase.java,
                      DriveNotAuthenticatedException.java, StorageErrors.java
  .../proposal/pdf/ProposalPdfRenderer.java, BriefPdfRenderer.java
  .../proposal/api/ProposalController.java, EventIntakeController.java (+ dto)
  test: domain/GenerateProposalUseCaseTest, domain/SubmitIntakeUseCaseTest,
        pdf/ProposalPdfRendererTest, pdf/BriefPdfRendererTest,
        api/ProposalControllerTest, api/EventIntakeControllerTest
config-repo/proposal-service.yml
infrastructure/api-gateway/.../application.yml            MODIFY: chat, proposals, generate-proposal, event-intake routes
infrastructure/api-gateway/.../GatewayRouteDefinitionTest.java  MODIFY
pom.xml                                                   MODIFY: three modules
```

---

### Task 1: bloom-pdf — Arabic-aware text for PDFBox

**Files:**
- Modify: `platform/bloom-bom/pom.xml`
- Create: `platform/bloom-pdf/pom.xml`
- Copy: `E:\Bloom_Event_Manager\src\server\assets\fonts\Amiri-Regular.ttf`, `Amiri-Bold.ttf` → `platform/bloom-pdf/src/main/resources/fonts/`
- Create: `platform/bloom-pdf/src/main/java/com/bloom/eventmanager/pdf/ArabicText.java`, `ScriptRuns.java`, `PdfFonts.java`, `MixedTextWriter.java`
- Test: `platform/bloom-pdf/src/test/java/com/bloom/eventmanager/pdf/ArabicTextTest.java`, `ScriptRunsTest.java`, `MixedTextWriterTest.java`
- Modify: `pom.xml` (add `<module>platform/bloom-pdf</module>` after `platform/bloom-events`)

**Interfaces:**
- Produces:
  - `ArabicText.isArabic(String) -> boolean`; `ArabicText.shapeForPdf(String) -> String` (shaped + visually reordered, ready to draw left-to-right)
  - `ScriptRuns.Run(String text, boolean arabic, boolean bold)`; `ScriptRuns.split(String line) -> List<Run>`
  - `PdfFonts.forDocument(PDDocument) -> PdfFonts` with `regular()`, `bold()`, `italic()`, `mono()`, `monoBold()`, `arabic()`, `arabicBold()`; `PdfFonts.width(PDFont, String, float size) -> float`
  - `MixedTextWriter.writeLine(PDPageContentStream, List<Run>, float x, float y, float maxWidth, float latinSize, float arabicSize, PdfFonts) -> float` returning the y after the (possibly wrapped) line

- [ ] **Step 1: Write the failing tests**

`ArabicTextTest.java`:

```java
package com.bloom.eventmanager.pdf;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ArabicTextTest {

    @Test
    void detectsArabicScript() {
        assertThat(ArabicText.isArabic("مرحبا")).isTrue();
        assertThat(ArabicText.isArabic("Hello مرحبا")).isTrue();
        assertThat(ArabicText.isArabic("Hello")).isFalse();
        assertThat(ArabicText.isArabic("")).isFalse();
        assertThat(ArabicText.isArabic(null)).isFalse();
    }

    @Test
    void shapingJoinsLettersIntoPresentationForms() {
        String shaped = ArabicText.shapeForPdf("سلام");

        assertThat(shaped).isNotEqualTo("سلام");
        // Presentation Forms-B block: U+FE70–U+FEFF
        assertThat(shaped.chars().anyMatch(c -> c >= 0xFE70 && c <= 0xFEFF)).isTrue();
    }

    @Test
    void reorderingPlacesTheLogicallyFirstLetterAtTheVisualRight() {
        // Logical "سلام" starts with س; after visual reordering for LTR drawing it ends with the س form.
        String shaped = ArabicText.shapeForPdf("سلام");
        char last = shaped.charAt(shaped.length() - 1);

        assertThat(last).isBetween('\uFEB1', '\uFEB4'); // SEEN presentation forms
    }

    @Test
    void latinTextPassesThroughUntouched() {
        assertThat(ArabicText.shapeForPdf("Hello, world 123")).isEqualTo("Hello, world 123");
    }
}
```

`ScriptRunsTest.java`:

```java
package com.bloom.eventmanager.pdf;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ScriptRunsTest {

    @Test
    void splitsBoldMarkdownIntoRuns() {
        List<ScriptRuns.Run> runs = ScriptRuns.split("Theme **Concept**: a party");

        assertThat(runs).containsExactly(
                new ScriptRuns.Run("Theme ", false, false),
                new ScriptRuns.Run("Concept", false, true),
                new ScriptRuns.Run(": a party", false, false));
    }

    @Test
    void switchesRunsWhenScriptChangesAndNeutralsStickToTheCurrentRun() {
        List<ScriptRuns.Run> runs = ScriptRuns.split("Hello, مرحبا 123 world");

        assertThat(runs).containsExactly(
                new ScriptRuns.Run("Hello, ", false, false),
                new ScriptRuns.Run("مرحبا 123 ", true, false),
                new ScriptRuns.Run("world", false, false));
    }

    @Test
    void boldSurvivesScriptSwitching() {
        List<ScriptRuns.Run> runs = ScriptRuns.split("**Bloom بلوم**");

        assertThat(runs).containsExactly(
                new ScriptRuns.Run("Bloom ", false, true),
                new ScriptRuns.Run("بلوم", true, true));
    }

    @Test
    void anAllArabicLineIsOneRun() {
        assertThat(ScriptRuns.split("فعاليات بناء الفريق")).singleElement()
                .satisfies(run -> assertThat(run.arabic()).isTrue());
    }

    @Test
    void emptyLineYieldsNoRuns() {
        assertThat(ScriptRuns.split("")).isEmpty();
    }
}
```

`MixedTextWriterTest.java`:

```java
package com.bloom.eventmanager.pdf;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MixedTextWriterTest {

    @Test
    void drawsMixedArabicAndEnglishAndWrapsLongLines() throws IOException {
        byte[] pdf;
        float yAfter;
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            PdfFonts fonts = PdfFonts.forDocument(doc);
            try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
                yAfter = MixedTextWriter.writeLine(content,
                        ScriptRuns.split("Welcome to **Bloom** فعاليات بناء الفريق and a very long English tail that must wrap onto a second line for sure"),
                        50, 700, 200, 12, 11, fonts);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            pdf = out.toByteArray();
        }

        assertThat(yAfter).isLessThan(700 - 12); // at least two lines consumed
        try (PDDocument reloaded = Loader.loadPDF(pdf)) {
            String text = new PDFTextStripper().getText(reloaded);
            assertThat(text).contains("Welcome").contains("Bloom").contains("wrap");
        }
    }

    @Test
    void fontsAreAvailablePerDocument() throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PdfFonts fonts = PdfFonts.forDocument(doc);

            assertThat(fonts.arabic().getName()).contains("Amiri");
            assertThat(fonts.arabicBold().getName()).contains("Amiri");
            assertThat(PdfFonts.width(fonts.regular(), "Hello", 12)).isGreaterThan(0);
        }
    }
}
```

- [ ] **Step 2: Create the module**

`platform/bloom-pdf/pom.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>com.bloom.eventmanager</groupId>
        <artifactId>bloom-parent</artifactId>
        <version>0.0.1-SNAPSHOT</version>
        <relativePath>../../pom.xml</relativePath>
    </parent>

    <artifactId>bloom-pdf</artifactId>
    <name>Bloom PDF</name>

    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>com.bloom.eventmanager</groupId>
                <artifactId>bloom-bom</artifactId>
                <version>${project.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
        </dependencies>
    </dependencyManagement>

    <dependencies>
        <dependency>
            <groupId>org.apache.pdfbox</groupId>
            <artifactId>pdfbox</artifactId>
        </dependency>
        <dependency>
            <groupId>com.ibm.icu</groupId>
            <artifactId>icu4j</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
```

Add to `platform/bloom-bom/pom.xml` `<dependencyManagement>`:

```xml
            <dependency>
                <groupId>com.bloom.eventmanager</groupId>
                <artifactId>bloom-pdf</artifactId>
                <version>${project.version}</version>
            </dependency>
```

Copy fonts:

```bash
mkdir -p platform/bloom-pdf/src/main/resources/fonts
cp /e/Bloom_Event_Manager/src/server/assets/fonts/Amiri-Regular.ttf platform/bloom-pdf/src/main/resources/fonts/
cp /e/Bloom_Event_Manager/src/server/assets/fonts/Amiri-Bold.ttf platform/bloom-pdf/src/main/resources/fonts/
```

Add `<module>platform/bloom-pdf</module>` to the root `pom.xml`.

- [ ] **Step 3: Run to verify failure**

Run: `./mvnw -q -pl platform/bloom-pdf test`
Expected: COMPILATION FAILURE — `cannot find symbol: class ArabicText`

- [ ] **Step 4: Implement**

`ArabicText.java`:

```java
package com.bloom.eventmanager.pdf;

import java.util.regex.Pattern;

import com.ibm.icu.text.ArabicShaping;
import com.ibm.icu.text.ArabicShapingException;
import com.ibm.icu.text.Bidi;

/**
 * Arabic text preparation for a left-to-right PDF content stream.
 *
 * <p>V1 reshaped with arabic-persian-reshaper and then reversed the string by
 * hand. ICU4J does both steps properly: {@link ArabicShaping} joins letters
 * into presentation forms and {@link Bidi} applies the Unicode Bidirectional
 * Algorithm, so digits and Latin fragments inside Arabic text keep their order.
 */
public final class ArabicText {

    private static final Pattern ARABIC = Pattern.compile("[\\u0600-\\u06FF]");
    private static final ArabicShaping SHAPER = new ArabicShaping(ArabicShaping.LETTERS_SHAPE);

    private ArabicText() {
    }

    public static boolean isArabic(String text) {
        return text != null && ARABIC.matcher(text).find();
    }

    /** Shaped and visually reordered; draw the result left to right with an Arabic font. */
    public static String shapeForPdf(String text) {
        if (!isArabic(text)) {
            return text;
        }
        try {
            String shaped = SHAPER.shape(text);
            Bidi bidi = new Bidi();
            bidi.setPara(shaped, Bidi.LEVEL_DEFAULT_LTR, null);
            return bidi.writeReordered(Bidi.DO_MIRRORING);
        }
        catch (ArabicShapingException ex) {
            return text;
        }
    }
}
```

`ScriptRuns.java`:

```java
package com.bloom.eventmanager.pdf;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits one line into runs that can each be drawn with a single font:
 * first by {@code **bold**} markdown, then by script. Neutral characters
 * (spaces, digits, punctuation) stay with the run they follow, as in V1's
 * renderMixedLine.
 */
public final class ScriptRuns {

    public record Run(String text, boolean arabic, boolean bold) {
    }

    private static final Pattern BOLD = Pattern.compile("\\*\\*(.+?)\\*\\*");

    private ScriptRuns() {
    }

    public static List<Run> split(String line) {
        List<Run> runs = new ArrayList<>();
        if (line == null || line.isEmpty()) {
            return runs;
        }
        Matcher matcher = BOLD.matcher(line);
        int last = 0;
        while (matcher.find()) {
            if (matcher.start() > last) {
                splitByScript(line.substring(last, matcher.start()), false, runs);
            }
            splitByScript(matcher.group(1), true, runs);
            last = matcher.end();
        }
        if (last < line.length()) {
            splitByScript(line.substring(last), false, runs);
        }
        return runs;
    }

    private static void splitByScript(String text, boolean bold, List<Run> into) {
        StringBuilder current = new StringBuilder();
        boolean currentArabic = false;
        boolean started = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean arabic = c >= 0x0600 && c <= 0x06FF;
            boolean strong = arabic || Character.isLetter(c);
            if (!started) {
                current.append(c);
                currentArabic = arabic;
                started = true;
            }
            else if (!strong || arabic == currentArabic) {
                current.append(c);
            }
            else {
                into.add(new Run(current.toString(), currentArabic, bold));
                current.setLength(0);
                current.append(c);
                currentArabic = arabic;
            }
        }
        if (current.length() > 0) {
            into.add(new Run(current.toString(), currentArabic, bold));
        }
    }
}
```

`PdfFonts.java`:

```java
package com.bloom.eventmanager.pdf;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

/** Fonts are bound to a document in PDFBox, so one instance per PDDocument. */
public final class PdfFonts {

    private final PDFont regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    private final PDFont bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
    private final PDFont italic = new PDType1Font(Standard14Fonts.FontName.HELVETICA_OBLIQUE);
    private final PDFont mono = new PDType1Font(Standard14Fonts.FontName.COURIER);
    private final PDFont monoBold = new PDType1Font(Standard14Fonts.FontName.COURIER_BOLD);
    private final PDFont arabic;
    private final PDFont arabicBold;

    private PdfFonts(PDDocument document) {
        this.arabic = load(document, "/fonts/Amiri-Regular.ttf");
        this.arabicBold = load(document, "/fonts/Amiri-Bold.ttf");
    }

    public static PdfFonts forDocument(PDDocument document) {
        return new PdfFonts(document);
    }

    private static PDFont load(PDDocument document, String resource) {
        try (InputStream in = PdfFonts.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Missing bundled font " + resource);
            }
            return PDType0Font.load(document, in);
        }
        catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    public PDFont regular() { return regular; }
    public PDFont bold() { return bold; }
    public PDFont italic() { return italic; }
    public PDFont mono() { return mono; }
    public PDFont monoBold() { return monoBold; }
    public PDFont arabic() { return arabic; }
    public PDFont arabicBold() { return arabicBold; }

    public PDFont forRun(ScriptRuns.Run run) {
        if (run.arabic()) {
            return run.bold() ? arabicBold : arabic;
        }
        return run.bold() ? bold : regular;
    }

    public static float width(PDFont font, String text, float size) {
        try {
            return font.getStringWidth(text) / 1000f * size;
        }
        catch (IOException | IllegalArgumentException ex) {
            return text.length() * size * 0.5f;
        }
    }
}
```

`MixedTextWriter.java`:

```java
package com.bloom.eventmanager.pdf;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDFont;

/**
 * Draws a line of {@link ScriptRuns.Run}s starting at (x, y), wrapping words
 * within {@code maxWidth}. Arabic runs are shaped per drawn segment and set in
 * the Amiri font at {@code arabicSize}; Latin runs in Helvetica at
 * {@code latinSize} — V1 used 11pt and 12pt respectively.
 *
 * @return the baseline y after the last drawn line
 */
public final class MixedTextWriter {

    private MixedTextWriter() {
    }

    public static float writeLine(PDPageContentStream content, List<ScriptRuns.Run> runs, float x, float y,
            float maxWidth, float latinSize, float arabicSize, PdfFonts fonts) throws IOException {
        float leading = Math.max(latinSize, arabicSize) * 1.4f;
        float cursorX = x;
        float baseline = y;

        for (ScriptRuns.Run run : runs) {
            PDFont font = fonts.forRun(run);
            float size = run.arabic() ? arabicSize : latinSize;
            for (String word : wordsWithSpaces(run.text())) {
                String drawable = run.arabic() ? ArabicText.shapeForPdf(word) : word;
                float width = PdfFonts.width(font, drawable, size);
                if (cursorX > x && cursorX + width > x + maxWidth) {
                    baseline -= leading;
                    cursorX = x;
                    if (word.isBlank()) {
                        continue;
                    }
                }
                content.beginText();
                content.setFont(font, size);
                content.newLineAtOffset(cursorX, baseline);
                content.showText(drawable);
                content.endText();
                cursorX += width;
            }
        }
        return baseline - leading;
    }

    /** Splits keeping each trailing space attached to its word so widths stay exact. */
    static List<String> wordsWithSpaces(String text) {
        List<String> words = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (char c : text.toCharArray()) {
            current.append(c);
            if (c == ' ') {
                words.add(current.toString());
                current.setLength(0);
            }
        }
        if (current.length() > 0) {
            words.add(current.toString());
        }
        return words;
    }
}
```

Note on `showText` with Type0 fonts: PDFBox throws `IllegalArgumentException` for glyphs the font lacks; Amiri covers Arabic presentation forms, Latin and digits, so mixed segments are safe. Helvetica (WinAnsi) cannot show Arabic — which is why runs are split by script before any drawing.

- [ ] **Step 5: Run to verify passing**

Run: `./mvnw -q -pl platform/bloom-pdf test`
Expected: PASS — 4 + 5 + 2 tests

- [ ] **Step 6: Commit**

```bash
git add pom.xml platform/bloom-bom/pom.xml platform/bloom-pdf
git commit -m "feat(pdf): bloom-pdf with ICU4J Arabic shaping, script runs and mixed-text writer"
```

---

### Task 2: chat-service — module, Mongo session store in V1's shape

**Files:**
- Create: `services/chat-service/pom.xml`, `src/main/resources/application.yml`
- Create: `.../chat/ChatServiceApplication.java`, `.../chat/config/ChatProperties.java`
- Create: `.../chat/domain/ChatMessage.java`, `ChatSessionDocument.java`, `ChatSessionRepository.java`, `ChatSessionStore.java`
- Test: `.../chat/domain/ChatSessionStoreIntegrationTests.java`
- Modify: `pom.xml` (add `<module>services/chat-service</module>` after `services/storage-service`)

**Interfaces:**
- Produces:
  - `ChatMessage(String role, String content, Instant timestamp, List<String> sources, String thoughts)`
  - `ChatSessionDocument` — `@Document("chatsessions")`, fields `id`, `sessionId` (unique), `messages`, `createdAt`, `updatedAt`
  - `ChatSessionStore` with `Optional<ChatSessionDocument> find(String sessionId)`, `List<ChatMessage> recentMessages(String sessionId, int limit)`, `void append(String sessionId, List<ChatMessage> messages)` (upsert), `List<ChatSessionDocument> summaries()` (updatedAt desc), `List<ChatSessionDocument> mentioning(String company)`, `long countMentioning(String company)` (cached 60 s)
  - `ChatProperties(int topK, int historyLimit)` — defaults 15 / 6

- [ ] **Step 1: Write the failing test**

```java
package com.bloom.eventmanager.chat.domain;

import java.time.Instant;
import java.util.List;

import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "spring.config.import=",
        "eureka.client.enabled=false",
        "spring.cloud.discovery.enabled=false",
        "spring.data.mongodb.auto-index-creation=true"
})
class ChatSessionStoreIntegrationTests {

    @Container
    @ServiceConnection
    static MongoDBContainer mongo = new MongoDBContainer("mongo:8.0");

    @Autowired
    private ChatSessionStore store;

    @Autowired
    private MongoTemplate template;

    @BeforeEach
    void clean() {
        template.getCollection("chatsessions").drop();
    }

    @Test
    void appendCreatesTheSessionThenAppendsInOrder() {
        store.append("s1", List.of(msg("user", "hi"), msg("model", "hello")));
        store.append("s1", List.of(msg("user", "more")));

        ChatSessionDocument session = store.find("s1").orElseThrow();
        assertThat(session.getMessages()).extracting(ChatMessage::content).containsExactly("hi", "hello", "more");
        assertThat(session.getCreatedAt()).isNotNull();
        assertThat(session.getUpdatedAt()).isAfterOrEqualTo(session.getCreatedAt());
        assertThat(template.getCollection("chatsessions").countDocuments()).isEqualTo(1);
    }

    @Test
    void writesV1sMongooseShape() {
        store.append("s1", List.of(new ChatMessage("model", "answer", Instant.parse("2026-09-15T10:00:00Z"),
                List.of("guide.pdf"), "thinking")));

        Document raw = template.getCollection("chatsessions").find().first();
        assertThat(raw).isNotNull();
        assertThat(raw.getString("sessionId")).isEqualTo("s1");
        Document message = raw.getList("messages", Document.class).get(0);
        assertThat(message.getString("role")).isEqualTo("model");
        assertThat(message.getList("sources", String.class)).containsExactly("guide.pdf");
        assertThat(message.getString("thoughts")).isEqualTo("thinking");
        assertThat(message.getDate("timestamp")).isNotNull();
    }

    @Test
    void readsASessionWrittenByV1() {
        template.getCollection("chatsessions").insertOne(new Document("sessionId", "session_1_abc")
                .append("messages", List.of(new Document("role", "user").append("content", "Plan for Acme")
                        .append("timestamp", new java.util.Date()).append("sources", List.of())))
                .append("createdAt", new java.util.Date()).append("updatedAt", new java.util.Date()));

        assertThat(store.recentMessages("session_1_abc", 6)).singleElement()
                .satisfies(m -> assertThat(m.content()).isEqualTo("Plan for Acme"));
    }

    @Test
    void recentMessagesReturnsTheTail() {
        store.append("s1", List.of(msg("user", "1"), msg("model", "2"), msg("user", "3"), msg("model", "4")));

        assertThat(store.recentMessages("s1", 2)).extracting(ChatMessage::content).containsExactly("3", "4");
        assertThat(store.recentMessages("missing", 2)).isEmpty();
    }

    @Test
    void summariesAreNewestFirst() throws InterruptedException {
        store.append("old", List.of(msg("user", "a")));
        Thread.sleep(5);
        store.append("new", List.of(msg("user", "b")));

        assertThat(store.summaries()).extracting(ChatSessionDocument::getSessionId).containsExactly("new", "old");
    }

    @Test
    void findsAndCountsSessionsMentioningACompanyCaseInsensitively() {
        store.append("s1", List.of(msg("user", "Ideas for ACME Corp offsite")));
        store.append("s2", List.of(msg("user", "Something else"), msg("model", "acme corp likes puzzles")));
        store.append("s3", List.of(msg("user", "Globex")));

        assertThat(store.mentioning("Acme Corp")).extracting(ChatSessionDocument::getSessionId)
                .containsExactlyInAnyOrder("s1", "s2");
        assertThat(store.countMentioning("acme corp")).isEqualTo(2);
        assertThat(store.countMentioning("A.C.M.E (Corp)")).isZero(); // regex metacharacters are escaped
    }

    private static ChatMessage msg(String role, String content) {
        return new ChatMessage(role, content, Instant.now(), List.of(), null);
    }
}
```

- [ ] **Step 2: Create the module**

`services/chat-service/pom.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>com.bloom.eventmanager</groupId>
        <artifactId>bloom-parent</artifactId>
        <version>0.0.1-SNAPSHOT</version>
        <relativePath>../../pom.xml</relativePath>
    </parent>

    <artifactId>chat-service</artifactId>
    <name>Chat Service</name>

    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>com.bloom.eventmanager</groupId>
                <artifactId>bloom-bom</artifactId>
                <version>${project.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
        </dependencies>
    </dependencyManagement>

    <dependencies>
        <dependency>
            <groupId>com.bloom.eventmanager</groupId>
            <artifactId>bloom-common</artifactId>
        </dependency>
        <dependency>
            <groupId>com.bloom.eventmanager</groupId>
            <artifactId>bloom-pdf</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-webmvc</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-mongodb</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-cache</artifactId>
        </dependency>
        <dependency>
            <groupId>com.github.ben-manes.caffeine</groupId>
            <artifactId>caffeine</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-openfeign</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-netflix-eureka-client</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-config</artifactId>
        </dependency>

        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-webmvc-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-testcontainers</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>testcontainers-mongodb</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>testcontainers-junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

`src/main/resources/application.yml`:

```yaml
spring:
  application:
    name: chat-service
  config:
    import: optional:configserver:${CONFIG_SERVER_URL:http://localhost:8888}
  cache:
    cache-names: chatCountForCompany
    caffeine:
      # V1: CACHE_TTL_CHAT_COUNT_MS = 60 s. Changes on any chat, which we cannot invalidate precisely.
      spec: maximumSize=500,expireAfterWrite=60s

server:
  port: 8085
```

`ChatServiceApplication.java`:

```java
package com.bloom.eventmanager.chat;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cloud.openfeign.EnableFeignClients;

@SpringBootApplication(scanBasePackages = "com.bloom.eventmanager")
@ConfigurationPropertiesScan
@EnableCaching
@EnableFeignClients(basePackages = "com.bloom.eventmanager.chat.client")
public class ChatServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ChatServiceApplication.class, args);
    }
}
```

`config/ChatProperties.java`:

```java
package com.bloom.eventmanager.chat.config;

import jakarta.validation.constraints.Min;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** V1: ragConfig.topK = 15; ChatWithDocumentsUseCase kept the last 6 messages. */
@Validated
@ConfigurationProperties(prefix = "bloom.chat")
public record ChatProperties(@DefaultValue("15") @Min(1) int topK, @DefaultValue("6") @Min(1) int historyLimit) {
}
```

- [ ] **Step 3: Run to verify failure**

Run: `./mvnw -q -pl services/chat-service test -Dtest=ChatSessionStoreIntegrationTests`
Expected: COMPILATION FAILURE — `cannot find symbol: class ChatSessionStore`

- [ ] **Step 4: Implement**

`domain/ChatMessage.java`:

```java
package com.bloom.eventmanager.chat.domain;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/** V1's MessageSchema plus the {@code thoughts} field MongoChatRepository wrote. Roles: "user" | "model". */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatMessage(String role, String content, Instant timestamp, List<String> sources, String thoughts) {
}
```

`domain/ChatSessionDocument.java`:

```java
package com.bloom.eventmanager.chat.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import com.fasterxml.jackson.annotation.JsonIgnore;

/** V1's Mongoose ChatSession model: collection "chatsessions". */
@Document(collection = "chatsessions")
public class ChatSessionDocument {

    @Id
    @JsonIgnore
    private String id;

    @Indexed(unique = true)
    private String sessionId;

    private List<ChatMessage> messages = new ArrayList<>();

    private Instant createdAt;

    private Instant updatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public List<ChatMessage> getMessages() { return messages; }
    public void setMessages(List<ChatMessage> messages) { this.messages = messages; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
```

`domain/ChatSessionRepository.java`:

```java
package com.bloom.eventmanager.chat.domain;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

public interface ChatSessionRepository extends MongoRepository<ChatSessionDocument, String> {

    Optional<ChatSessionDocument> findBySessionId(String sessionId);

    List<ChatSessionDocument> findAll(Sort sort);

    /** V1: ChatSession.countDocuments({ "messages.content": { $regex, $options: "i" } }). */
    @Query("{ 'messages.content': { $regex: ?0, $options: 'i' } }")
    List<ChatSessionDocument> findMentioning(String escapedRegex);

    @Query(value = "{ 'messages.content': { $regex: ?0, $options: 'i' } }", count = true)
    long countMentioning(String escapedRegex);
}
```

`domain/ChatSessionStore.java`:

```java
package com.bloom.eventmanager.chat.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

/** V1's MongoChatRepository. */
@Service
public class ChatSessionStore {

    private final ChatSessionRepository repository;

    public ChatSessionStore(ChatSessionRepository repository) {
        this.repository = repository;
    }

    public Optional<ChatSessionDocument> find(String sessionId) {
        return repository.findBySessionId(sessionId);
    }

    public List<ChatMessage> recentMessages(String sessionId, int limit) {
        return repository.findBySessionId(sessionId)
                .map(session -> {
                    List<ChatMessage> all = session.getMessages();
                    return List.copyOf(all.subList(Math.max(0, all.size() - limit), all.size()));
                })
                .orElse(List.of());
    }

    /** Upsert: creates the session on first append (V1's findOneAndUpdate with upsert). */
    public void append(String sessionId, List<ChatMessage> messages) {
        Instant now = Instant.now();
        ChatSessionDocument session = repository.findBySessionId(sessionId).orElseGet(() -> {
            ChatSessionDocument fresh = new ChatSessionDocument();
            fresh.setSessionId(sessionId);
            fresh.setCreatedAt(now);
            return fresh;
        });
        List<ChatMessage> updated = new ArrayList<>(session.getMessages());
        for (ChatMessage message : messages) {
            updated.add(message.timestamp() == null
                    ? new ChatMessage(message.role(), message.content(), now, message.sources(), message.thoughts())
                    : message);
        }
        session.setMessages(updated);
        session.setUpdatedAt(now);
        repository.save(session);
    }

    public List<ChatSessionDocument> summaries() {
        return repository.findAll(Sort.by(Sort.Direction.DESC, "updatedAt"));
    }

    public List<ChatSessionDocument> mentioning(String companyName) {
        return repository.findMentioning(Pattern.quote(companyName));
    }

    @Cacheable(cacheNames = "chatCountForCompany", key = "#companyName.toLowerCase()")
    public long countMentioning(String companyName) {
        return repository.countMentioning(Pattern.quote(companyName));
    }
}
```

`Pattern.quote` wraps the name in `\Q…\E`, which MongoDB's PCRE-compatible regex engine honours — the metacharacter test above pins that.

- [ ] **Step 5: Run to verify passing**

Run: `./mvnw -q -pl services/chat-service test -Dtest=ChatSessionStoreIntegrationTests`
Expected: PASS — 6 tests (skipped without Docker)

- [ ] **Step 6: Commit**

```bash
git add pom.xml services/chat-service
git commit -m "feat(chat): module and Mongo session store in V1's chatsessions shape"
```

---

### Task 3: chat-service — the Event Advisor use case

**Files:**
- Create: `.../chat/client/dto/RetrieveRequest.java`, `RetrieveResponse.java`, `ChunkMatch.java`, `GenerateRequest.java`, `GenerateResponse.java`, `ChatBackupRequest.java`, `ChatBackupResponse.java`
- Create: `.../chat/client/RetrievalClient.java`, `AiClient.java`, `StorageClient.java`
- Create: `.../chat/domain/ChatResult.java`, `ChatWithDocumentsUseCase.java`
- Test: `.../chat/domain/ChatWithDocumentsUseCaseTest.java`

**Interfaces:**
- Consumes: `retrieval-service` `POST /api/v1/retrieve`; `ai-service` `POST /api/v1/ai/generate`; `ChatSessionStore`
- Produces: `ChatWithDocumentsUseCase.execute(String message, String sessionId) -> ChatResult(String answer, String thoughts, String sessionId, List<String> sources)`

- [ ] **Step 1: Write the failing test**

```java
package com.bloom.eventmanager.chat.domain;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.bloom.eventmanager.chat.client.AiClient;
import com.bloom.eventmanager.chat.client.RetrievalClient;
import com.bloom.eventmanager.chat.client.dto.ChunkMatch;
import com.bloom.eventmanager.chat.client.dto.GenerateRequest;
import com.bloom.eventmanager.chat.client.dto.GenerateResponse;
import com.bloom.eventmanager.chat.client.dto.RetrieveRequest;
import com.bloom.eventmanager.chat.client.dto.RetrieveResponse;
import com.bloom.eventmanager.chat.config.ChatProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatWithDocumentsUseCaseTest {

    private final RetrievalClient retrieval = mock(RetrievalClient.class);
    private final AiClient ai = mock(AiClient.class);
    private final ChatSessionStore store = mock(ChatSessionStore.class);
    private final ChatWithDocumentsUseCase useCase = new ChatWithDocumentsUseCase(retrieval, ai, store, new ChatProperties(15, 6));

    @Test
    void buildsV1sPromptWithNumberedSourcesHistoryAndTheMessage() {
        when(store.recentMessages("s1", 6)).thenReturn(List.of(
                new ChatMessage("user", "earlier question", Instant.now(), List.of(), null),
                new ChatMessage("model", "earlier answer", Instant.now(), List.of(), null)));
        when(retrieval.retrieve(any())).thenReturn(new RetrieveResponse("success", List.of(
                new ChunkMatch("c1", "puzzle ideas", "guide.pdf", 1, 0.9),
                new ChunkMatch("c2", "venue notes", "brief.pdf", 1, 0.8),
                new ChunkMatch("c3", "more puzzles", "guide.pdf", 2, 0.7)), null));
        when(ai.generate(any())).thenReturn(new GenerateResponse("the plan", null));

        ChatResult result = useCase.execute("plan a team day", "s1");

        ArgumentCaptor<GenerateRequest> request = ArgumentCaptor.forClass(GenerateRequest.class);
        verify(ai).generate(request.capture());
        String prompt = request.getValue().input();
        assertThat(prompt).startsWith("You are an event planner for Bloom.")
                .contains("[Source 1: guide.pdf]\npuzzle ideas")
                .contains("[Source 2: brief.pdf]\nvenue notes")
                .contains("[Source 1: guide.pdf]\nmore puzzles")
                .contains("USER: earlier question\nMODEL: earlier answer")
                .contains("USER: plan a team day")
                .contains("**Step 1 — Escapism**")
                .doesNotContain("CRITICAL — NO REPETITION");
        assertThat(request.getValue().config().includeThoughts()).isTrue();
        assertThat(request.getValue().config().temperature()).isEqualTo(1.2);
        assertThat(result.sources()).containsExactly("guide.pdf", "brief.pdf");
        assertThat(result.sessionId()).isEqualTo("s1");
    }

    @Test
    void addsTheAntiRepetitionBlockOnceThePriorSuggestionsAreLongEnough() {
        when(store.recentMessages("s1", 6)).thenReturn(List.of(
                new ChatMessage("model", "x".repeat(150), Instant.now(), List.of(), null)));
        when(retrieval.retrieve(any())).thenReturn(new RetrieveResponse("success", List.of(), null));
        when(ai.generate(any())).thenReturn(new GenerateResponse("new plan", null));

        useCase.execute("another idea", "s1");

        ArgumentCaptor<GenerateRequest> request = ArgumentCaptor.forClass(GenerateRequest.class);
        verify(ai).generate(request.capture());
        assertThat(request.getValue().input()).contains("CRITICAL — NO REPETITION");
    }

    @Test
    void retrievesPlainlyAtTopKAndAddsTheTeambuildingSearchWhenMentioned() {
        when(retrieval.retrieve(argThat(r -> r != null && "plan team building".equals(r.query()))))
                .thenReturn(new RetrieveResponse("success", List.of(new ChunkMatch("c1", "a", "a.pdf", 1, 0.9)), null));
        when(retrieval.retrieve(argThat(r -> r != null && r.query().startsWith("Teambuilding activities Candy Love"))))
                .thenReturn(new RetrieveResponse("success", List.of(
                        new ChunkMatch("c1", "a", "a.pdf", 1, 0.9), new ChunkMatch("c9", "candy", "tb.pdf", 1, 0.5)), null));
        when(ai.generate(any())).thenReturn(new GenerateResponse("ok", null));

        ChatResult result = useCase.execute("plan team building", null);

        ArgumentCaptor<RetrieveRequest> requests = ArgumentCaptor.forClass(RetrieveRequest.class);
        verify(retrieval, times(2)).retrieve(requests.capture());
        assertThat(requests.getAllValues().get(0)).isEqualTo(new RetrieveRequest("plan team building", 15, false));
        assertThat(requests.getAllValues().get(1)).isEqualTo(new RetrieveRequest(
                "Teambuilding activities Candy Love Caption This Spot the Movies Big Puzzle Age Lineup", 10, false));
        assertThat(result.sources()).containsExactly("a.pdf", "tb.pdf"); // c1 deduped
        assertThat(result.sessionId()).matches("session_\\d+_[a-z0-9]{7}");
    }

    @Test
    void extractsPlanTagsAsTheAnswerAndTheRestAsThoughts() {
        when(retrieval.retrieve(any())).thenReturn(new RetrieveResponse("success", List.of(), null));
        when(ai.generate(any())).thenReturn(new GenerateResponse("Let me think.\n<plan>The actual plan</plan>", "native thought"));

        ChatResult result = useCase.execute("q", "s1");

        assertThat(result.answer()).isEqualTo("The actual plan");
        assertThat(result.thoughts()).isEqualTo("native thought\n\n---\n\nLet me think.");
    }

    @Test
    void stripsThinkTagsIntoThoughts() {
        when(retrieval.retrieve(any())).thenReturn(new RetrieveResponse("success", List.of(), null));
        when(ai.generate(any())).thenReturn(new GenerateResponse("<think>hmm</think>Final answer", null));

        ChatResult result = useCase.execute("q", "s1");

        assertThat(result.answer()).isEqualTo("Final answer");
        assertThat(result.thoughts()).isEqualTo("hmm");
    }

    @Test
    void movesShortOpeningFluffIntoThoughts() {
        when(retrieval.retrieve(any())).thenReturn(new RetrieveResponse("success", List.of(), null));
        when(ai.generate(any())).thenReturn(new GenerateResponse("Sure, here is a plan.\n\n**Theme Concept**: Space", null));

        ChatResult result = useCase.execute("q", "s1");

        assertThat(result.answer()).isEqualTo("**Theme Concept**: Space");
        assertThat(result.thoughts()).isEqualTo("Sure, here is a plan.");
    }

    @Test
    void persistsUserAndModelMessagesWithSourcesAndThoughts() {
        when(retrieval.retrieve(any())).thenReturn(new RetrieveResponse("success",
                List.of(new ChunkMatch("c1", "a", "a.pdf", 1, 0.9)), null));
        when(ai.generate(any())).thenReturn(new GenerateResponse("answer", "why"));

        useCase.execute("hello", "s1");

        ArgumentCaptor<List<ChatMessage>> saved = ArgumentCaptor.captor();
        verify(store).append(eq("s1"), saved.capture());
        assertThat(saved.getValue()).hasSize(2);
        assertThat(saved.getValue().get(0).role()).isEqualTo("user");
        assertThat(saved.getValue().get(0).content()).isEqualTo("hello");
        assertThat(saved.getValue().get(1).role()).isEqualTo("model");
        assertThat(saved.getValue().get(1).sources()).containsExactly("a.pdf");
        assertThat(saved.getValue().get(1).thoughts()).isEqualTo("why");
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./mvnw -q -pl services/chat-service test -Dtest=ChatWithDocumentsUseCaseTest`
Expected: COMPILATION FAILURE — `package com.bloom.eventmanager.chat.client does not exist`

- [ ] **Step 3: Implement the clients**

`client/dto/RetrieveRequest.java`:

```java
package com.bloom.eventmanager.chat.client.dto;

/** {@code useAI} keeps V1's wire spelling. Chat always sends false: V1's chat used a plain single-vector search. */
public record RetrieveRequest(String query, int topK, boolean useAI) {
}
```

`client/dto/ChunkMatch.java`, `RetrieveResponse.java`:

```java
package com.bloom.eventmanager.chat.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ChunkMatch(String id, String text, String source, int page, double score) {
}
```

```java
package com.bloom.eventmanager.chat.client.dto;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record RetrieveResponse(String status, List<ChunkMatch> chunks, Map<String, Object> metadata) {
}
```

`client/dto/GenerateRequest.java`, `GenerateResponse.java`:

```java
package com.bloom.eventmanager.chat.client.dto;

public record GenerateRequest(String input, Config config) {

    public record Config(Boolean includeThoughts, Double temperature, Integer maxOutputTokens) {
    }
}
```

```java
package com.bloom.eventmanager.chat.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GenerateResponse(String text, String thoughts) {
}
```

`client/dto/ChatBackupRequest.java`, `ChatBackupResponse.java`:

```java
package com.bloom.eventmanager.chat.client.dto;

public record ChatBackupRequest(String sessionId, String contentBase64) {
}
```

```java
package com.bloom.eventmanager.chat.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ChatBackupResponse(String folderId, String fileId, String webViewLink) {
}
```

`client/RetrievalClient.java`, `AiClient.java`, `StorageClient.java`:

```java
package com.bloom.eventmanager.chat.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import com.bloom.eventmanager.chat.client.dto.RetrieveRequest;
import com.bloom.eventmanager.chat.client.dto.RetrieveResponse;

@FeignClient(name = "retrieval-service")
public interface RetrievalClient {

    @PostMapping("/api/v1/retrieve")
    RetrieveResponse retrieve(@RequestBody RetrieveRequest request);
}
```

```java
package com.bloom.eventmanager.chat.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import com.bloom.eventmanager.chat.client.dto.GenerateRequest;
import com.bloom.eventmanager.chat.client.dto.GenerateResponse;

@FeignClient(name = "ai-service")
public interface AiClient {

    @PostMapping("/api/v1/ai/generate")
    GenerateResponse generate(@RequestBody GenerateRequest request);
}
```

```java
package com.bloom.eventmanager.chat.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import com.bloom.eventmanager.chat.client.dto.ChatBackupRequest;
import com.bloom.eventmanager.chat.client.dto.ChatBackupResponse;

@FeignClient(name = "storage-service")
public interface StorageClient {

    @PostMapping("/api/v1/storage/chat-backups")
    ChatBackupResponse uploadChatBackup(@RequestBody ChatBackupRequest request);
}
```

- [ ] **Step 4: Implement the use case**

`domain/ChatResult.java`:

```java
package com.bloom.eventmanager.chat.domain;

import java.util.List;

public record ChatResult(String answer, String thoughts, String sessionId, List<String> sources) {
}
```

`domain/ChatWithDocumentsUseCase.java`:

```java
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
```

- [ ] **Step 5: Run to verify passing**

Run: `./mvnw -q -pl services/chat-service test -Dtest=ChatWithDocumentsUseCaseTest`
Expected: PASS — 7 tests

- [ ] **Step 6: Commit**

```bash
git add services/chat-service
git commit -m "feat(chat): Event Advisor use case with V1 prompt, tag handling and history"
```

---

### Task 4: chat-service — PDF export, REST API, config

**Files:**
- Create: `.../chat/pdf/ChatPdfRenderer.java`
- Create: `.../chat/api/dto/ChatRequest.java`
- Create: `.../chat/api/ChatController.java`, `.../chat/api/SessionController.java`
- Create: `config-repo/chat-service.yml`
- Test: `.../chat/pdf/ChatPdfRendererTest.java`, `.../chat/api/ChatControllerTest.java`, `.../chat/api/SessionControllerTest.java`

**Interfaces:**
- Produces (all on `chat-service`, V1's response shapes):

```
POST /api/v1/chat {message, sessionId?}         -> {status:"success", answer, thoughts?, sessionId, sources}
GET  /api/v1/chat/sessions                      -> {status:"success", sessions:[{sessionId, createdAt, updatedAt, messages:[first]}]}
GET  /api/v1/chat/sessions/{id}                 -> {status:"success", session} | 404
POST /api/v1/chat/sessions/{id}/pdf             -> application/pdf
POST /api/v1/chat/sessions/{id}/backup          -> {status:"success", message, drive:{folderId, fileId, webViewLink}}
GET  /api/v1/chat/sessions/search?mentions=     -> ChatSessionDocument[]        (for proposal-service)
GET  /api/v1/chat/sessions/count?mentions=      -> {count}                      (for proposal-service)
```
- `ChatPdfRenderer.render(ChatSessionDocument) -> byte[]`

- [ ] **Step 1: Write the failing tests**

`ChatPdfRendererTest.java`:

```java
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
```

`ChatControllerTest.java`:

```java
package com.bloom.eventmanager.chat.api;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.bloom.eventmanager.chat.domain.ChatResult;
import com.bloom.eventmanager.chat.domain.ChatWithDocumentsUseCase;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ChatController.class)
class ChatControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ChatWithDocumentsUseCase useCase;

    @Test
    void answersInV1sShape() throws Exception {
        when(useCase.execute("hi", null)).thenReturn(new ChatResult("hello", "why", "session_1_abcdefg", List.of("g.pdf")));

        mockMvc.perform(post("/api/v1/chat").contentType(MediaType.APPLICATION_JSON).content("{\"message\": \"hi\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.answer").value("hello"))
                .andExpect(jsonPath("$.thoughts").value("why"))
                .andExpect(jsonPath("$.sessionId").value("session_1_abcdefg"))
                .andExpect(jsonPath("$.sources[0]").value("g.pdf"));
    }

    @Test
    void rejectsABlankMessage() throws Exception {
        mockMvc.perform(post("/api/v1/chat").contentType(MediaType.APPLICATION_JSON).content("{\"message\": \" \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));

        verify(useCase, never()).execute(any(), any());
    }
}
```

`SessionControllerTest.java`:

```java
package com.bloom.eventmanager.chat.api;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.bloom.eventmanager.chat.client.StorageClient;
import com.bloom.eventmanager.chat.client.dto.ChatBackupRequest;
import com.bloom.eventmanager.chat.client.dto.ChatBackupResponse;
import com.bloom.eventmanager.chat.domain.ChatMessage;
import com.bloom.eventmanager.chat.domain.ChatSessionDocument;
import com.bloom.eventmanager.chat.domain.ChatSessionStore;
import com.bloom.eventmanager.chat.pdf.ChatPdfRenderer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SessionController.class)
class SessionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ChatSessionStore store;

    @MockitoBean
    private ChatPdfRenderer renderer;

    @MockitoBean
    private StorageClient storage;

    private static ChatSessionDocument session(String id, String... contents) {
        ChatSessionDocument s = new ChatSessionDocument();
        s.setSessionId(id);
        s.setCreatedAt(Instant.parse("2026-09-15T10:00:00Z"));
        s.setUpdatedAt(Instant.parse("2026-09-15T10:05:00Z"));
        s.setMessages(java.util.Arrays.stream(contents)
                .map(c -> new ChatMessage("user", c, Instant.now(), List.of(), null)).toList());
        return s;
    }

    @Test
    void listsSessionsWithOnlyTheFirstMessageLikeV1sProjection() throws Exception {
        when(store.summaries()).thenReturn(List.of(session("s1", "first", "second")));

        mockMvc.perform(get("/api/v1/chat/sessions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.sessions[0].sessionId").value("s1"))
                .andExpect(jsonPath("$.sessions[0].messages.length()").value(1))
                .andExpect(jsonPath("$.sessions[0].messages[0].content").value("first"))
                .andExpect(jsonPath("$.sessions[0].createdAt").value("2026-09-15T10:00:00Z"));
    }

    @Test
    void returnsAFullSessionOr404() throws Exception {
        when(store.find("s1")).thenReturn(Optional.of(session("s1", "a", "b")));
        when(store.find("nope")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/chat/sessions/s1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.session.messages.length()").value(2));
        mockMvc.perform(get("/api/v1/chat/sessions/nope"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));
    }

    @Test
    void exportsAPdf() throws Exception {
        when(store.find("s1")).thenReturn(Optional.of(session("s1", "a")));
        when(renderer.render(any())).thenReturn("%PDF-1.7 fake".getBytes());

        mockMvc.perform(post("/api/v1/chat/sessions/s1/pdf"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(content().bytes("%PDF-1.7 fake".getBytes()));
    }

    @Test
    void backsUpToDriveViaStorageService() throws Exception {
        when(store.find("session_1_abcdefg")).thenReturn(Optional.of(session("session_1_abcdefg", "a")));
        when(renderer.render(any())).thenReturn(new byte[] { 1, 2 });
        when(storage.uploadChatBackup(argThat(r -> r != null && r.sessionId().equals("session_1_abcdefg")
                && r.contentBase64().equals(java.util.Base64.getEncoder().encodeToString(new byte[] { 1, 2 })))))
                .thenReturn(new ChatBackupResponse("folder", "file", "link"));

        mockMvc.perform(post("/api/v1/chat/sessions/session_1_abcdefg/backup"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.message").value("Chat backup created successfully"))
                .andExpect(jsonPath("$.drive.fileId").value("file"))
                .andExpect(jsonPath("$.drive.webViewLink").value("link"));
    }

    @Test
    void searchesAndCountsMentions() throws Exception {
        when(store.mentioning("Acme")).thenReturn(List.of(session("s1", "Acme plan")));
        when(store.countMentioning("Acme")).thenReturn(1L);

        mockMvc.perform(get("/api/v1/chat/sessions/search").param("mentions", "Acme"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].sessionId").value("s1"));
        mockMvc.perform(get("/api/v1/chat/sessions/count").param("mentions", "Acme"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1));
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./mvnw -q -pl services/chat-service test -Dtest='ChatPdfRendererTest,ChatControllerTest,SessionControllerTest'`
Expected: COMPILATION FAILURE — `cannot find symbol: class ChatPdfRenderer`

- [ ] **Step 3: Implement the renderer**

`pdf/ChatPdfRenderer.java`:

```java
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
            ensure(18);
            content.setNonStrokingColor(Color.BLACK);
            y = MixedTextWriter.writeLine(content, ScriptRuns.split(text), MARGIN + indent, y,
                    width - 2 * MARGIN - indent, 12, 11, fonts);
            if (y < MARGIN) {
                newPage();
            }
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
            content.showText(text);
            content.endText();
        }

        void close() throws IOException {
            content.close();
        }
    }
}
```

- [ ] **Step 4: Implement the controllers**

`api/dto/ChatRequest.java`:

```java
package com.bloom.eventmanager.chat.api.dto;

import jakarta.validation.constraints.NotBlank;

public record ChatRequest(@NotBlank(message = "Message is required") String message, String sessionId) {
}
```

`api/ChatController.java`:

```java
package com.bloom.eventmanager.chat.api;

import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bloom.eventmanager.chat.api.dto.ChatRequest;
import com.bloom.eventmanager.chat.domain.ChatResult;
import com.bloom.eventmanager.chat.domain.ChatWithDocumentsUseCase;

@RestController
@RequestMapping("/api/v1/chat")
public class ChatController {

    private final ChatWithDocumentsUseCase useCase;

    public ChatController(ChatWithDocumentsUseCase useCase) {
        this.useCase = useCase;
    }

    @PostMapping
    public Map<String, Object> chat(@Valid @RequestBody ChatRequest request) {
        ChatResult result = useCase.execute(request.message(), request.sessionId());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "success");
        body.put("answer", result.answer());
        if (result.thoughts() != null) {
            body.put("thoughts", result.thoughts());
        }
        body.put("sessionId", result.sessionId());
        body.put("sources", result.sources());
        return body;
    }
}
```

`api/SessionController.java`:

```java
package com.bloom.eventmanager.chat.api;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.bloom.eventmanager.chat.client.StorageClient;
import com.bloom.eventmanager.chat.client.dto.ChatBackupRequest;
import com.bloom.eventmanager.chat.client.dto.ChatBackupResponse;
import com.bloom.eventmanager.chat.domain.ChatSessionDocument;
import com.bloom.eventmanager.chat.domain.ChatSessionStore;
import com.bloom.eventmanager.chat.pdf.ChatPdfRenderer;
import com.bloom.eventmanager.common.error.ExternalServiceException;
import com.bloom.eventmanager.common.error.NotFoundException;

@RestController
@RequestMapping("/api/v1/chat/sessions")
public class SessionController {

    private final ChatSessionStore store;
    private final ChatPdfRenderer renderer;
    private final StorageClient storage;

    public SessionController(ChatSessionStore store, ChatPdfRenderer renderer, StorageClient storage) {
        this.store = store;
        this.renderer = renderer;
        this.storage = storage;
    }

    /** V1: ChatSession.find({}, {sessionId, createdAt, messages: {$slice: 1}}).sort({updatedAt: -1}). */
    @GetMapping
    public Map<String, Object> sessions() {
        List<Map<String, Object>> summaries = store.summaries().stream().map(session -> {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("sessionId", session.getSessionId());
            summary.put("createdAt", session.getCreatedAt());
            summary.put("updatedAt", session.getUpdatedAt());
            summary.put("messages", session.getMessages().isEmpty() ? List.of() : List.of(session.getMessages().get(0)));
            return summary;
        }).toList();
        return Map.of("status", "success", "sessions", summaries);
    }

    @GetMapping("/search")
    public List<ChatSessionDocument> search(@RequestParam("mentions") String mentions) {
        return store.mentioning(mentions);
    }

    @GetMapping("/count")
    public Map<String, Long> count(@RequestParam("mentions") String mentions) {
        return Map.of("count", store.countMentioning(mentions));
    }

    @GetMapping("/{sessionId}")
    public Map<String, Object> session(@PathVariable String sessionId) {
        return Map.of("status", "success", "session", require(sessionId));
    }

    @PostMapping(value = "/{sessionId}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> pdf(@PathVariable String sessionId) {
        byte[] pdf = renderer.render(require(sessionId));
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header("Content-Disposition", "attachment; filename=\"Chat_" + sessionId + ".pdf\"")
                .body(pdf);
    }

    @PostMapping("/{sessionId}/backup")
    public Map<String, Object> backup(@PathVariable String sessionId) {
        byte[] pdf = renderer.render(require(sessionId));
        ChatBackupResponse drive;
        try {
            drive = storage.uploadChatBackup(new ChatBackupRequest(sessionId, Base64.getEncoder().encodeToString(pdf)));
        }
        catch (feign.FeignException.Unauthorized unauthorized) {
            throw new DriveNotAuthenticated();
        }
        catch (RuntimeException ex) {
            throw new ExternalServiceException("Chat backup upload failed", ex);
        }
        return Map.of("status", "success", "message", "Chat backup created successfully", "drive", drive);
    }

    private ChatSessionDocument require(String sessionId) {
        return store.find(sessionId).orElseThrow(() -> new NotFoundException("Session not found"));
    }

    /** storage-service said Drive is not authenticated; re-raise in V1's shape for the SPA. */
    static final class DriveNotAuthenticated extends com.bloom.eventmanager.common.error.BloomException {
        DriveNotAuthenticated() {
            super(com.bloom.eventmanager.common.error.ErrorCode.DRIVE_NOT_AUTHENTICATED, "Google Drive not authenticated.");
        }

        @Override
        public Map<String, Object> properties() {
            return Map.of("authUrl", "/api/v1/rag/sync/auth?force=true",
                    "error", "Google Drive not authenticated.", "message", "Google Drive not authenticated.");
        }
    }
}
```

`config-repo/chat-service.yml`:

```yaml
server:
  port: 8085

spring:
  data:
    mongodb:
      uri: ${MONGO_URI:mongodb://localhost:27017}
      # Overrides any database in the URI. Set MONGO_CHAT_DB to V1's database to reuse existing sessions.
      database: ${MONGO_CHAT_DB:chatdb}
      auto-index-creation: true

bloom:
  chat:
    topK: ${RAG_TOP_K:15}
    history-limit: 6

management:
  endpoint:
    health:
      group:
        readiness:
          include: readinessState,mongo
```

- [ ] **Step 5: Run every chat-service test**

Run: `./mvnw -q -pl services/chat-service test`
Expected: PASS — 6 + 7 + 2 + 2 + 5 = 22 (Mongo tests skip without Docker)

- [ ] **Step 6: Commit**

```bash
git add services/chat-service config-repo/chat-service.yml
git commit -m "feat(chat): PDF export with Arabic support, session API, Drive backup"
```

---

### Task 5: proposal-service — module, clients, proposal generation

**Files:**
- Create: `services/proposal-service/pom.xml`, `src/main/resources/application.yml`
- Create: `.../proposal/ProposalServiceApplication.java`
- Create: `.../proposal/client/dto/EmbedRequest.java`, `EmbedResponse.java`, `GenerateRequest.java`, `GenerateResponse.java`, `ChunkMatch.java`, `ChunkSearchRequest.java`, `ChunksBySourcesRequest.java`, `ChatSession.java`, `ChatMessage.java`, `CountResponse.java`, `CompanyFolder.java`, `CompanyFileRequest.java`, `CompanyFileResponse.java`
- Create: `.../proposal/client/KnowledgeClient.java`, `AiClient.java`, `ChatClient.java`, `StorageClient.java`
- Create: `.../proposal/domain/ProposalSlide.java`, `GeneratedProposal.java`, `GenerateProposalUseCase.java`, `DriveNotAuthenticatedException.java`, `StorageErrors.java`
- Test: `.../proposal/domain/GenerateProposalUseCaseTest.java`
- Modify: `pom.xml` (add `<module>services/proposal-service</module>` after `services/chat-service`)

**Interfaces:**
- Produces:
  - `ProposalSlide(int slideNumber, String type, String title, String subtitle, Map<String, Object> content)`
  - `GeneratedProposal(List<ProposalSlide> slides, String companyName, String generatedAt)`
  - `GenerateProposalUseCase.execute(String companyName) -> GeneratedProposal`
  - `StorageErrors.translate(RuntimeException) -> RuntimeException` — Feign 401 from storage-service becomes `DriveNotAuthenticatedException`
  - Feign clients: `KnowledgeClient.allSources()`, `chunksBySources(ChunksBySourcesRequest)`, `search(ChunkSearchRequest)`; `AiClient.embed(EmbedRequest)`, `generate(GenerateRequest)`; `ChatClient.sessionsMentioning(String)`, `countMentioning(String)`; `StorageClient.companies()`, `uploadCompanyFile(CompanyFileRequest)`

- [ ] **Step 1: Write the failing test**

```java
package com.bloom.eventmanager.proposal.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import com.bloom.eventmanager.common.error.BloomException;
import com.bloom.eventmanager.common.error.NotFoundException;
import com.bloom.eventmanager.proposal.client.AiClient;
import com.bloom.eventmanager.proposal.client.ChatClient;
import com.bloom.eventmanager.proposal.client.KnowledgeClient;
import com.bloom.eventmanager.proposal.client.dto.ChatMessage;
import com.bloom.eventmanager.proposal.client.dto.ChatSession;
import com.bloom.eventmanager.proposal.client.dto.ChunkMatch;
import com.bloom.eventmanager.proposal.client.dto.ChunksBySourcesRequest;
import com.bloom.eventmanager.proposal.client.dto.EmbedResponse;
import com.bloom.eventmanager.proposal.client.dto.GenerateRequest;
import com.bloom.eventmanager.proposal.client.dto.GenerateResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GenerateProposalUseCaseTest {

    private final KnowledgeClient knowledge = mock(KnowledgeClient.class);
    private final AiClient ai = mock(AiClient.class);
    private final ChatClient chat = mock(ChatClient.class);
    private final GenerateProposalUseCase useCase = new GenerateProposalUseCase(knowledge, ai, chat, JsonMapper.builder().build());

    private static final String NINE_SLIDES = """
            {"slides": [
              {"slideNumber": 9, "type": "cover", "title": "Acme", "subtitle": "Event Proposal",
               "content": {"companyName": "wrong", "eventDate": "TBD", "tagline": "Go", "preparedBy": "someone"}},
              {"slideNumber": 1, "type": "summary", "title": "Executive Summary", "content": {"overview": "o", "highlights": ["h"]}}
            ]}""";

    @Test
    void assemblesBriefChunksChatHistoryAndTeambuildingKnowledgeIntoV1sPrompt() {
        when(knowledge.allSources()).thenReturn(List.of("Acme Corp/brief.pdf", "guide.pdf", "acme-notes.pdf"));
        when(knowledge.chunksBySources(new ChunksBySourcesRequest(List.of("Acme Corp/brief.pdf", "acme-notes.pdf"), 50)))
                .thenReturn(List.of(new ChunkMatch("c1", "40 people, June", "Acme Corp/brief.pdf", 1, 1.0),
                        new ChunkMatch("c2", "loves puzzles", "acme-notes.pdf", 1, 1.0)));
        when(chat.sessionsMentioning("Acme Corp")).thenReturn(List.of(new ChatSession("s1",
                List.of(new ChatMessage("user", "Acme Corp wants mystery", Instant.now(), List.of(), null),
                        new ChatMessage("model", "Try a heist theme", Instant.now(), List.of(), null)),
                Instant.parse("2026-09-01T10:00:00Z"), Instant.parse("2026-09-01T10:05:00Z"))));
        when(ai.embed(argThat(r -> r != null && r.text().equals("Acme Corp event teambuilding activities agenda proposal"))))
                .thenReturn(new EmbedResponse(new float[] { 1f }));
        when(knowledge.search(any())).thenReturn(List.of(new ChunkMatch("t1", "Candy Love rules", "tb.pdf", 1, 0.9)));
        when(ai.generate(any())).thenReturn(new GenerateResponse(NINE_SLIDES, null));

        GeneratedProposal proposal = useCase.execute("Acme Corp");

        ArgumentCaptor<GenerateRequest> request = ArgumentCaptor.forClass(GenerateRequest.class);
        verify(ai).generate(request.capture());
        String prompt = request.getValue().input();
        assertThat(prompt).startsWith("You are an expert Event Proposal Writer for Bloom")
                .contains("CLIENT BRIEF & EVENT DATA (from \"Acme Corp\")")
                .contains("40 people, June\n\n---\n\nloves puzzles")
                .contains("EVENT ADVISOR CONVERSATIONS (discussions about Acme Corp)")
                .contains("=== Chat Session (")
                .contains("CLIENT: Acme Corp wants mystery\nADVISOR: Try a heist theme")
                .contains("[tb.pdf] Candy Love rules")
                .contains("Generate EXACTLY 9 slides")
                .contains("Output ONLY valid JSON. No markdown, no explanation, no code fences.");
        assertThat(request.getValue().config().temperature()).isEqualTo(0.7);
        assertThat(request.getValue().config().maxOutputTokens()).isEqualTo(8192);
        assertThat(proposal.companyName()).isEqualTo("Acme Corp");
        assertThat(proposal.generatedAt()).isNotBlank();
    }

    @Test
    void renumbersSlidesAndForcesCoverBranding() {
        stubHappyPath();

        GeneratedProposal proposal = useCase.execute("Acme Corp");

        assertThat(proposal.slides()).extracting(ProposalSlide::slideNumber).containsExactly(1, 2);
        Map<String, Object> cover = proposal.slides().get(0).content();
        assertThat(cover).containsEntry("companyName", "Acme Corp").containsEntry("preparedBy", "Bloom Events");
    }

    @Test
    void acceptsABareArrayOfSlides() {
        stubHappyPath();
        when(ai.generate(any())).thenReturn(new GenerateResponse(
                "```json\n[{\"type\": \"summary\", \"title\": \"S\", \"content\": {}}]\n```", null));

        assertThat(useCase.execute("Acme Corp").slides()).singleElement()
                .satisfies(slide -> assertThat(slide.slideNumber()).isEqualTo(1));
    }

    @Test
    void omitsTheChatSectionWhenNoSessionMentionsTheCompany() {
        stubHappyPath();
        when(chat.sessionsMentioning("Acme Corp")).thenReturn(List.of());

        useCase.execute("Acme Corp");

        ArgumentCaptor<GenerateRequest> request = ArgumentCaptor.forClass(GenerateRequest.class);
        verify(ai).generate(request.capture());
        assertThat(request.getValue().input()).doesNotContain("EVENT ADVISOR CONVERSATIONS");
    }

    @Test
    void chatServiceFailureIsToleratedLikeV1() {
        stubHappyPath();
        when(chat.sessionsMentioning("Acme Corp")).thenThrow(new RuntimeException("chat down"));

        assertThat(useCase.execute("Acme Corp").slides()).isNotEmpty();
    }

    @Test
    void unknownCompanyIs404WithV1sMessage() {
        when(knowledge.allSources()).thenReturn(List.of("guide.pdf"));

        assertThatThrownBy(() -> useCase.execute("Nobody"))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("No data found for \"Nobody\". Make sure a brief has been submitted and synced.");
        verify(ai, never()).generate(any());
    }

    @Test
    void blankCompanyIs400() {
        assertThatThrownBy(() -> useCase.execute("  "))
                .isInstanceOf(BloomException.class)
                .hasMessage("Company name is required");
    }

    @Test
    void unparseableModelOutputIs500WithV1sMessage() {
        stubHappyPath();
        when(ai.generate(any())).thenReturn(new GenerateResponse("Sorry, I cannot do that.", null));

        assertThatThrownBy(() -> useCase.execute("Acme Corp"))
                .isInstanceOf(BloomException.class)
                .hasMessage("Failed to generate proposal. AI response was not valid JSON.");
    }

    private void stubHappyPath() {
        when(knowledge.allSources()).thenReturn(List.of("Acme Corp/brief.pdf"));
        when(knowledge.chunksBySources(any())).thenReturn(List.of(new ChunkMatch("c1", "brief text", "Acme Corp/brief.pdf", 1, 1.0)));
        when(chat.sessionsMentioning(any())).thenReturn(List.of());
        when(ai.embed(any())).thenReturn(new EmbedResponse(new float[] { 1f }));
        when(knowledge.search(any())).thenReturn(List.of());
        when(ai.generate(any())).thenReturn(new GenerateResponse(NINE_SLIDES, null));
    }
}
```

- [ ] **Step 2: Create the module**

`services/proposal-service/pom.xml` — identical to `chat-service`'s POM with these three changes: `<artifactId>proposal-service</artifactId>`, `<name>Proposal Service</name>`, and **remove** the four Mongo-related dependencies (`spring-boot-starter-data-mongodb`, `spring-boot-testcontainers`, `testcontainers-mongodb`, `testcontainers-junit-jupiter`) and the two cache dependencies (`spring-boot-starter-cache`, `caffeine`). Keep `bloom-common`, `bloom-pdf`, webmvc, validation, actuator, openfeign, eureka client, config client, `spring-boot-starter-test`, `spring-boot-webmvc-test`.

`src/main/resources/application.yml`:

```yaml
spring:
  application:
    name: proposal-service
  config:
    import: optional:configserver:${CONFIG_SERVER_URL:http://localhost:8888}

server:
  port: 8087
```

`ProposalServiceApplication.java`:

```java
package com.bloom.eventmanager.proposal;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

@SpringBootApplication(scanBasePackages = "com.bloom.eventmanager")
@EnableFeignClients(basePackages = "com.bloom.eventmanager.proposal.client")
public class ProposalServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProposalServiceApplication.class, args);
    }
}
```

Add `<module>services/proposal-service</module>` to the root `pom.xml`.

- [ ] **Step 3: Run to verify failure**

Run: `./mvnw -q -pl services/proposal-service test -Dtest=GenerateProposalUseCaseTest`
Expected: COMPILATION FAILURE — `package com.bloom.eventmanager.proposal.client does not exist`

- [ ] **Step 4: Implement the clients**

All DTOs live in `com.bloom.eventmanager.proposal.client.dto`; every response record carries `@JsonIgnoreProperties(ignoreUnknown = true)`.

```java
public record EmbedRequest(String text) {}
public record EmbedResponse(float[] embedding) {}
public record GenerateRequest(String input, Config config) {
    public record Config(Boolean includeThoughts, Double temperature, Integer maxOutputTokens) {}
}
public record GenerateResponse(String text, String thoughts) {}
public record ChunkMatch(String id, String text, String source, int page, double score) {}
public record ChunkSearchRequest(float[] embedding, int topK) {}
public record ChunksBySourcesRequest(List<String> sources, int limit) {}
public record ChatMessage(String role, String content, Instant timestamp, List<String> sources, String thoughts) {}
public record ChatSession(String sessionId, List<ChatMessage> messages, Instant createdAt, Instant updatedAt) {}
public record CountResponse(long count) {}
public record CompanyFolder(String id, String name, int briefCount) {}
public record CompanyFileRequest(String companyName, String fileName, String mimeType, String contentBase64) {}
public record CompanyFileResponse(String folderId, String fileId, String webViewLink) {}
```

Each goes in its own file with `package com.bloom.eventmanager.proposal.client.dto;` and the imports it needs (`java.util.List`, `java.time.Instant`, `com.fasterxml.jackson.annotation.JsonIgnoreProperties`).

`client/KnowledgeClient.java`:

```java
package com.bloom.eventmanager.proposal.client;

import java.util.List;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import com.bloom.eventmanager.proposal.client.dto.ChunkMatch;
import com.bloom.eventmanager.proposal.client.dto.ChunkSearchRequest;
import com.bloom.eventmanager.proposal.client.dto.ChunksBySourcesRequest;

@FeignClient(name = "knowledge-service")
public interface KnowledgeClient {

    @GetMapping("/api/v1/sources")
    List<String> allSources();

    @PostMapping("/api/v1/sources/chunks")
    List<ChunkMatch> chunksBySources(@RequestBody ChunksBySourcesRequest request);

    @PostMapping("/api/v1/chunks/search")
    List<ChunkMatch> search(@RequestBody ChunkSearchRequest request);
}
```

`client/AiClient.java`:

```java
package com.bloom.eventmanager.proposal.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import com.bloom.eventmanager.proposal.client.dto.EmbedRequest;
import com.bloom.eventmanager.proposal.client.dto.EmbedResponse;
import com.bloom.eventmanager.proposal.client.dto.GenerateRequest;
import com.bloom.eventmanager.proposal.client.dto.GenerateResponse;

@FeignClient(name = "ai-service")
public interface AiClient {

    @PostMapping("/api/v1/ai/embed")
    EmbedResponse embed(@RequestBody EmbedRequest request);

    @PostMapping("/api/v1/ai/generate")
    GenerateResponse generate(@RequestBody GenerateRequest request);
}
```

`client/ChatClient.java`:

```java
package com.bloom.eventmanager.proposal.client;

import java.util.List;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.bloom.eventmanager.proposal.client.dto.ChatSession;
import com.bloom.eventmanager.proposal.client.dto.CountResponse;

@FeignClient(name = "chat-service")
public interface ChatClient {

    @GetMapping("/api/v1/chat/sessions/search")
    List<ChatSession> sessionsMentioning(@RequestParam("mentions") String company);

    @GetMapping("/api/v1/chat/sessions/count")
    CountResponse countMentioning(@RequestParam("mentions") String company);
}
```

`client/StorageClient.java`:

```java
package com.bloom.eventmanager.proposal.client;

import java.util.List;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import com.bloom.eventmanager.proposal.client.dto.CompanyFileRequest;
import com.bloom.eventmanager.proposal.client.dto.CompanyFileResponse;
import com.bloom.eventmanager.proposal.client.dto.CompanyFolder;

@FeignClient(name = "storage-service")
public interface StorageClient {

    @GetMapping("/api/v1/storage/companies")
    List<CompanyFolder> companies();

    @PostMapping("/api/v1/storage/company-files")
    CompanyFileResponse uploadCompanyFile(@RequestBody CompanyFileRequest request);
}
```

`domain/DriveNotAuthenticatedException.java`, `domain/StorageErrors.java`:

```java
package com.bloom.eventmanager.proposal.domain;

import java.util.Map;

import com.bloom.eventmanager.common.error.BloomException;
import com.bloom.eventmanager.common.error.ErrorCode;

/** V1's requireDriveAuth response shape, raised when storage-service answers 401. */
public class DriveNotAuthenticatedException extends BloomException {

    private static final String MESSAGE = "Google Drive not authenticated.";

    public DriveNotAuthenticatedException() {
        super(ErrorCode.DRIVE_NOT_AUTHENTICATED, MESSAGE);
    }

    @Override
    public Map<String, Object> properties() {
        return Map.of("authUrl", "/api/v1/rag/sync/auth?force=true", "error", MESSAGE, "message", MESSAGE);
    }
}
```

```java
package com.bloom.eventmanager.proposal.domain;

import feign.FeignException;

import com.bloom.eventmanager.common.error.ExternalServiceException;

/** Turns storage-service Feign failures into the errors the SPA understands. */
public final class StorageErrors {

    private StorageErrors() {
    }

    public static RuntimeException translate(RuntimeException ex) {
        if (ex instanceof FeignException.Unauthorized) {
            return new DriveNotAuthenticatedException();
        }
        return new ExternalServiceException("storage-service call failed", ex);
    }
}
```

- [ ] **Step 5: Implement the use case**

`domain/ProposalSlide.java`, `GeneratedProposal.java`:

```java
package com.bloom.eventmanager.proposal.domain;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/** One of V1's nine slide types; {@code content} is the type-specific object, kept generic for rendering. */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProposalSlide(int slideNumber, String type, String title, String subtitle, Map<String, Object> content) {

    public ProposalSlide withNumber(int number) {
        return new ProposalSlide(number, type, title, subtitle, content);
    }

    public ProposalSlide withContent(Map<String, Object> newContent) {
        return new ProposalSlide(slideNumber, type, title, subtitle, newContent);
    }
}
```

```java
package com.bloom.eventmanager.proposal.domain;

import java.util.List;

public record GeneratedProposal(List<ProposalSlide> slides, String companyName, String generatedAt) {
}
```

`domain/GenerateProposalUseCase.java`:

```java
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

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
            log.warn("Could not retrieve chat history, continuing without it: {}", ex.getMessage());
        }

        // Step 4: teambuilding knowledge via a vector search
        float[] embedding = safely(() -> ai.embed(new EmbedRequest(companyName + " event teambuilding activities agenda proposal")), "ai-service").embedding();
        List<ChunkMatch> tbChunks = safely(() -> knowledge.search(new ChunkSearchRequest(embedding, 10)), "knowledge-service");
        String tbContext = tbChunks.stream().map(c -> "[" + c.source() + "] " + c.text()).collect(Collectors.joining("\n---\n"));

        // Step 5: generate and parse
        GenerateResponse response = safely(() -> ai.generate(new GenerateRequest(
                buildPrompt(companyName, contextText, tbContext, chatContext),
                new GenerateRequest.Config(null, 0.7, 8192))), "ai-service");

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
            return json.readValue(json.writeValueAsString(array), json.getTypeFactory().constructCollectionType(List.class, ProposalSlide.class));
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
```

- [ ] **Step 6: Run to verify passing**

Run: `./mvnw -q -pl services/proposal-service test -Dtest=GenerateProposalUseCaseTest`
Expected: PASS — 8 tests

- [ ] **Step 7: Commit**

```bash
git add pom.xml services/proposal-service
git commit -m "feat(proposal): module, Feign clients, V1 proposal generation use case"
```

---

### Task 6: proposal-service — proposal PDF, brief PDF, intake submission

**Files:**
- Create: `.../proposal/pdf/SlideCanvas.java`, `ProposalPdfRenderer.java`, `BriefPdfRenderer.java`
- Create: `.../proposal/domain/EventIntakeData.java`, `IntakeSubmission.java`, `SubmitIntakeUseCase.java`
- Test: `.../proposal/pdf/ProposalPdfRendererTest.java`, `.../proposal/pdf/BriefPdfRendererTest.java`, `.../proposal/domain/SubmitIntakeUseCaseTest.java`

**Interfaces:**
- Produces:
  - `ProposalPdfRenderer.render(List<ProposalSlide> slides, String companyName) -> byte[]` — 1280×720 landscape, one page per slide
  - `BriefPdfRenderer.render(EventIntakeData) -> byte[]` — A4 portrait, V1's "Event Intake & Customer Brief"
  - `EventIntakeData` — every field of V1's interface
  - `SubmitIntakeUseCase.execute(EventIntakeData) -> IntakeSubmission(boolean success, String message, String companyName, String folderName, String fileName, String folderId, String fileId, String viewLink)`

- [ ] **Step 1: Write the failing tests**

`ProposalPdfRendererTest.java`:

```java
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
```

`BriefPdfRendererTest.java`:

```java
package com.bloom.eventmanager.proposal.pdf;

import java.io.IOException;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import com.bloom.eventmanager.proposal.domain.EventIntakeData;

import static org.assertj.core.api.Assertions.assertThat;

class BriefPdfRendererTest {

    @Test
    void rendersAllNineSectionsWithLabelsResolved() throws IOException {
        EventIntakeData data = new EventIntakeData("Acme Corp", "Jane", "jane@acme.test", "June 2026", "Tech",
                List.of("Bonding"), "Fun", "Curiosity", "Open", List.of("Collaborative"), "Silos", "Surveys", "no",
                "40", "full-day", "balanced", List.of("25-40"), "hybrid", "yes", "not-sure", "Cairo", "Near metro",
                List.of("Egyptian"), "Arabic, English", List.of("Halal"), "Cairo", "None", List.of(),
                List.of("Employees"), List.of("Cross-team"), "Energised", List.of("Playful"), "Nothing scary",
                List.of("Lunch"), "1hour", "yes", List.of("Vegetarian"), "yes", "Tote bags", "Budget 50k");

        byte[] pdf = new BriefPdfRenderer().render(data);

        try (PDDocument doc = Loader.loadPDF(pdf)) {
            assertThat(doc.getNumberOfPages()).isGreaterThanOrEqualTo(1);
            String text = new PDFTextStripper().getText(doc);
            assertThat(text).contains("Acme Corp").contains("Event Intake & Customer Brief")
                    .contains("Client & Event Information").contains("Core Theme & Objectives")
                    .contains("Event Scope & Logistics").contains("Venue & Facilities")
                    .contains("Cultural Considerations").contains("Participant & Interaction Goals")
                    .contains("Activity Style & Tone").contains("Food & Amenities").contains("Additional Context")
                    .contains("Full-Day (approx. 6-8 hours, including breaks)")
                    .contains("A balanced mix of high-energy activities and focused downtime")
                    .contains("Hybrid (Indoor + Outdoor)")
                    .contains("Theatre / Stage: Yes").contains("Sound System: Not Sure / To Be Determined")
                    .contains("1 hour").contains("Yes (Tote bags)")
                    .contains("Generated by Bloom RAG System").contains("Page 1 of");
        }
    }

    @Test
    void emptyChecklistsSayNoneSelected() throws IOException {
        EventIntakeData data = new EventIntakeData("Acme", null, null, null, null, List.of(), null, null, null,
                List.of(), null, null, null, null, null, null, List.of(), null, null, null, null, null, List.of(), null,
                List.of(), null, null, List.of(), List.of(), List.of(), null, List.of(), null, List.of(), null, null,
                List.of(), null, null, null);

        try (PDDocument doc = Loader.loadPDF(new BriefPdfRenderer().render(data))) {
            assertThat(new PDFTextStripper().getText(doc)).contains("None selected").contains("—");
        }
    }
}
```

`SubmitIntakeUseCaseTest.java`:

```java
package com.bloom.eventmanager.proposal.domain;

import java.util.List;

import feign.FeignException;
import feign.Request;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.bloom.eventmanager.common.error.BloomException;
import com.bloom.eventmanager.proposal.client.StorageClient;
import com.bloom.eventmanager.proposal.client.dto.CompanyFileRequest;
import com.bloom.eventmanager.proposal.client.dto.CompanyFileResponse;
import com.bloom.eventmanager.proposal.pdf.BriefPdfRenderer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SubmitIntakeUseCaseTest {

    private final StorageClient storage = mock(StorageClient.class);
    private final BriefPdfRenderer renderer = mock(BriefPdfRenderer.class);
    private final SubmitIntakeUseCase useCase = new SubmitIntakeUseCase(storage, renderer);

    @Test
    void rendersTheBriefAndUploadsItIntoTheCompanyFolder() {
        EventIntakeData data = minimal("Acme/Corp: Ltd");
        when(renderer.render(data)).thenReturn(new byte[] { 1, 2, 3 });
        when(storage.uploadCompanyFile(any())).thenReturn(new CompanyFileResponse("folder", "file", "https://drive/view"));

        IntakeSubmission result = useCase.execute(data);

        ArgumentCaptor<CompanyFileRequest> request = ArgumentCaptor.forClass(CompanyFileRequest.class);
        verify(storage).uploadCompanyFile(request.capture());
        assertThat(request.getValue().companyName()).isEqualTo("Acme/Corp: Ltd");
        assertThat(request.getValue().fileName()).matches("Customer_Brief_\\d{4}-\\d{2}-\\d{2}T\\d{2}-\\d{2}-\\d{2}\\.pdf");
        assertThat(request.getValue().mimeType()).isEqualTo("application/pdf");
        assertThat(request.getValue().contentBase64()).isEqualTo("AQID");
        assertThat(result.success()).isTrue();
        assertThat(result.message()).isEqualTo("Brief submitted successfully and uploaded to Google Drive");
        assertThat(result.folderName()).isEqualTo("AcmeCorp Ltd");
        assertThat(result.folderId()).isEqualTo("folder");
        assertThat(result.fileId()).isEqualTo("file");
        assertThat(result.viewLink()).isEqualTo("https://drive/view");
    }

    @Test
    void requiresACompanyName() {
        assertThatThrownBy(() -> useCase.execute(minimal("  ")))
                .isInstanceOf(BloomException.class)
                .hasMessage("Company name is required");
    }

    @Test
    void translatesStorage401IntoDriveNotAuthenticated() {
        when(renderer.render(any())).thenReturn(new byte[] { 1 });
        when(storage.uploadCompanyFile(any())).thenThrow(new FeignException.Unauthorized("401",
                Request.create(Request.HttpMethod.POST, "/x", java.util.Map.of(), null, null, null), null, java.util.Map.of()));

        assertThatThrownBy(() -> useCase.execute(minimal("Acme"))).isInstanceOf(DriveNotAuthenticatedException.class);
    }

    static EventIntakeData minimal(String company) {
        return new EventIntakeData(company, null, null, null, null, List.of(), null, null, null, List.of(), null, null,
                null, null, null, null, List.of(), null, null, null, null, null, List.of(), null, List.of(), null, null,
                List.of(), List.of(), List.of(), null, List.of(), null, List.of(), null, null, List.of(), null, null, null);
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./mvnw -q -pl services/proposal-service test -Dtest='ProposalPdfRendererTest,BriefPdfRendererTest,SubmitIntakeUseCaseTest'`
Expected: COMPILATION FAILURE — `cannot find symbol: class ProposalPdfRenderer`

- [ ] **Step 3: Implement the slide canvas and proposal renderer**

`pdf/SlideCanvas.java` — the drawing primitives every slide renderer uses:

```java
package com.bloom.eventmanager.proposal.pdf;

import java.awt.Color;
import java.io.IOException;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;

import com.bloom.eventmanager.pdf.ArabicText;
import com.bloom.eventmanager.pdf.PdfFonts;

/** One 1280x720 slide. Arabic strings are shaped and right-aligned automatically. */
final class SlideCanvas implements AutoCloseable {

    static final float W = 1280f;
    static final float H = 720f;

    private final PDPageContentStream content;
    private final PdfFonts fonts;

    SlideCanvas(PDDocument doc, PdfFonts fonts) throws IOException {
        PDPage page = new PDPage(new PDRectangle(W, H));
        doc.addPage(page);
        this.content = new PDPageContentStream(doc, page);
        this.fonts = fonts;
    }

    /** y is measured from the TOP, as in V1's PDFKit coordinates. */
    private static float baseline(float yFromTop, float size) {
        return H - yFromTop - size;
    }

    void background(Color color) throws IOException {
        rect(0, 0, W, H, color);
    }

    void rect(float x, float yFromTop, float w, float h, Color fill) throws IOException {
        content.setNonStrokingColor(fill);
        content.addRect(x, H - yFromTop - h, w, h);
        content.fill();
    }

    void rectWithAlpha(float x, float yFromTop, float w, float h, Color fill, float alpha) throws IOException {
        PDExtendedGraphicsState state = new PDExtendedGraphicsState();
        state.setNonStrokingAlphaConstant(alpha);
        content.saveGraphicsState();
        content.setGraphicsStateParameters(state);
        rect(x, yFromTop, w, h, fill);
        content.restoreGraphicsState();
    }

    void strokeRect(float x, float yFromTop, float w, float h, Color color, float lineWidth) throws IOException {
        content.setStrokingColor(color);
        content.setLineWidth(lineWidth);
        content.addRect(x, H - yFromTop - h, w, h);
        content.stroke();
    }

    void line(float x1, float y1FromTop, float x2, float y2FromTop, Color color, float lineWidth) throws IOException {
        content.setStrokingColor(color);
        content.setLineWidth(lineWidth);
        content.moveTo(x1, H - y1FromTop);
        content.lineTo(x2, H - y2FromTop);
        content.stroke();
    }

    /** Draws {@code text} in a box starting at x with the given width; Arabic is shaped and right-aligned. */
    void text(String text, float x, float yFromTop, float width, float size, Weight weight, Color color, Align align) throws IOException {
        if (text == null || text.isEmpty()) {
            return;
        }
        boolean arabic = ArabicText.isArabic(text);
        PDFont font = pick(weight, arabic);
        String drawable = arabic ? ArabicText.shapeForPdf(text) : text;
        Align effective = arabic && align == Align.LEFT ? Align.RIGHT : align;
        float leading = size * 1.3f;
        float y = yFromTop;
        for (String line : wrap(drawable, font, size, width)) {
            float lineWidth = PdfFonts.width(font, line, size);
            float startX = switch (effective) {
                case LEFT -> x;
                case CENTER -> x + (width - lineWidth) / 2;
                case RIGHT -> x + width - lineWidth;
            };
            content.setNonStrokingColor(color);
            content.beginText();
            content.setFont(font, size);
            content.newLineAtOffset(startX, baseline(y, size));
            content.showText(line);
            content.endText();
            y += leading;
        }
    }

    /** Upper-cases Latin text; Arabic has no case and is left as is (V1 did the same). */
    void heading(String text, float x, float yFromTop, float width, float size, Color color, Align align) throws IOException {
        text(ArabicText.isArabic(text) ? text : text.toUpperCase(), x, yFromTop, width, size, Weight.BOLD, color, align);
    }

    private PDFont pick(Weight weight, boolean arabic) {
        if (arabic) {
            return weight == Weight.BOLD ? fonts.arabicBold() : fonts.arabic();
        }
        return switch (weight) {
            case BOLD -> fonts.bold();
            case ITALIC -> fonts.italic();
            case MONO -> fonts.mono();
            case MONO_BOLD -> fonts.monoBold();
            default -> fonts.regular();
        };
    }

    private static List<String> wrap(String text, PDFont font, float size, float width) {
        List<String> lines = new java.util.ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String word : text.split(" ")) {
            String candidate = current.isEmpty() ? word : current + " " + word;
            if (PdfFonts.width(font, candidate, size) > width && !current.isEmpty()) {
                lines.add(current.toString());
                current = new StringBuilder(word);
            }
            else {
                current = new StringBuilder(candidate);
            }
        }
        if (!current.isEmpty()) {
            lines.add(current.toString());
        }
        return lines;
    }

    @Override
    public void close() throws IOException {
        content.close();
    }

    enum Weight { REGULAR, BOLD, ITALIC, MONO, MONO_BOLD }

    enum Align { LEFT, CENTER, RIGHT }
}
```

`pdf/ProposalPdfRenderer.java`:

```java
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
            c.text("→ " + setup.get(i), 96, 264 + i * 24, 1060, 13, Weight.REGULAR, PAPER, Align.LEFT);
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
        for (String line : dump.split("\n")) {
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
```

- [ ] **Step 4: Implement the intake brief**

`domain/EventIntakeData.java` — V1's interface, every field:

```java
package com.bloom.eventmanager.proposal.domain;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record EventIntakeData(
        String companyName, String contactPerson, String contactEmailPhone, String targetEventDates, String customerType,
        List<String> primaryGoals, String otherGoal, String coreValuesThemes, String companyCulture,
        List<String> cultureAttributes, String negativeAttributes, String measureCulture, String governmentInvolvement,
        String expectedParticipants, String eventDuration, String energyPacing, List<String> ageGroup,
        String venueType, String hasTheatre, String hasSoundSystem, String facilityLocation, String venueNotes,
        List<String> culturalDiversity, String primaryLanguages, List<String> dietaryRestrictions, String regionalDistribution,
        String culturalSensitivities, List<String> cultureSensitivities,
        List<String> participantTypes, List<String> interactionGoals, String otherInteractionGoal, String audienceExperience,
        List<String> activityFeel, String activitiesToAvoid,
        List<String> cateringOptions, String lunchBreak, String foodBooths, List<String> cateringDetails,
        String giveaways, String giveawaysDetails, String additionalContext) {
}
```

`pdf/BriefPdfRenderer.java`:

```java
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
```

V1 drew the Bloom logo from `src/client/assets/bloom.jpeg` when present. The header band renders without it here; adding the image is a one-line `PDImageXObject.createFromByteArray` once the frontend assets move over in Phase 5.

`domain/IntakeSubmission.java`, `domain/SubmitIntakeUseCase.java`:

```java
package com.bloom.eventmanager.proposal.domain;

public record IntakeSubmission(boolean success, String message, String companyName, String folderName,
        String fileName, String folderId, String fileId, String viewLink) {
}
```

```java
package com.bloom.eventmanager.proposal.domain;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Base64;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.bloom.eventmanager.common.error.BloomException;
import com.bloom.eventmanager.common.error.ErrorCode;
import com.bloom.eventmanager.proposal.client.StorageClient;
import com.bloom.eventmanager.proposal.client.dto.CompanyFileRequest;
import com.bloom.eventmanager.proposal.client.dto.CompanyFileResponse;
import com.bloom.eventmanager.proposal.pdf.BriefPdfRenderer;

/** V1's SubmitProposalUseCase: render the brief, upload it into the company's Drive folder. */
@Service
public class SubmitIntakeUseCase {

    private static final Logger log = LoggerFactory.getLogger(SubmitIntakeUseCase.class);

    private final StorageClient storage;
    private final BriefPdfRenderer renderer;

    public SubmitIntakeUseCase(StorageClient storage, BriefPdfRenderer renderer) {
        this.storage = storage;
        this.renderer = renderer;
    }

    public IntakeSubmission execute(EventIntakeData data) {
        if (data.companyName() == null || data.companyName().isBlank()) {
            throw new BloomException(ErrorCode.VALIDATION_FAILED, "Company name is required");
        }
        // V1: new Date().toISOString().replace(/[:.]/g, "-").slice(0, 19) -> "2026-09-15T10-30-45"
        String stamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now()).replaceAll("[:.]", "-").substring(0, 19);
        String fileName = "Customer_Brief_" + stamp + ".pdf";
        byte[] pdf = renderer.render(data);

        CompanyFileResponse upload;
        try {
            upload = storage.uploadCompanyFile(new CompanyFileRequest(data.companyName(), fileName, "application/pdf",
                    Base64.getEncoder().encodeToString(pdf)));
        }
        catch (RuntimeException ex) {
            throw StorageErrors.translate(ex);
        }
        log.info("Customer brief uploaded for: {}", data.companyName());

        return new IntakeSubmission(true, "Brief submitted successfully and uploaded to Google Drive",
                data.companyName(), sanitizeFolderName(data.companyName()), fileName,
                upload.folderId(), upload.fileId(), upload.webViewLink());
    }

    /** Same rule storage-service applies (Phase 3b): strip path characters, keep spaces. */
    static String sanitizeFolderName(String name) {
        String cleaned = name.replaceAll("[<>:\"/\\\\|?*]", "").trim();
        return cleaned.length() > 100 ? cleaned.substring(0, 100) : cleaned;
    }
}
```

- [ ] **Step 5: Run to verify passing**

Run: `./mvnw -q -pl services/proposal-service test -Dtest='ProposalPdfRendererTest,BriefPdfRendererTest,SubmitIntakeUseCaseTest'`
Expected: PASS — 2 + 2 + 3

- [ ] **Step 6: Commit**

```bash
git add services/proposal-service
git commit -m "feat(proposal): proposal and brief PDF renderers, intake submission"
```

---

### Task 7: proposal-service — REST API, config, gateway routes

**Files:**
- Create: `.../proposal/api/dto/GenerateProposalRequest.java`, `SaveProposalRequest.java`
- Create: `.../proposal/api/ProposalController.java`, `EventIntakeController.java`
- Create: `config-repo/proposal-service.yml`
- Modify: `infrastructure/api-gateway/src/main/resources/application.yml`, `.../GatewayRouteDefinitionTest.java`
- Test: `.../proposal/api/ProposalControllerTest.java`, `.../proposal/api/EventIntakeControllerTest.java`

**Interfaces (proposal-service, V1's response shapes):**

```
GET  /api/v1/proposals/companies                 -> {companies:[{id, name, briefCount, chatCount}]}
POST /api/v1/proposals/generate {companyName}    -> {slides, companyName, generatedAt}
POST /api/v1/proposals/save {slides, companyName}     -> {success:true, fileName, fileId, viewLink}
POST /api/v1/proposals/download {slides, companyName} -> application/pdf, attachment "<company>_Proposal.pdf"
POST /api/v1/event-intake EventIntakeData        -> IntakeSubmission
```

Gateway: `/api/v1/rag/chat/**` → chat-service; `/api/v1/rag/proposals/**`, `/api/v1/rag/generate-proposal`, `/api/v1/rag/event-intake` → proposal-service. These are exactly the paths the React components call today.

- [ ] **Step 1: Write the failing tests**

`ProposalControllerTest.java`:

```java
package com.bloom.eventmanager.proposal.api;

import java.util.List;
import java.util.Map;

import feign.FeignException;
import feign.Request;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.bloom.eventmanager.proposal.client.ChatClient;
import com.bloom.eventmanager.proposal.client.StorageClient;
import com.bloom.eventmanager.proposal.client.dto.CompanyFileResponse;
import com.bloom.eventmanager.proposal.client.dto.CompanyFolder;
import com.bloom.eventmanager.proposal.client.dto.CountResponse;
import com.bloom.eventmanager.proposal.domain.GenerateProposalUseCase;
import com.bloom.eventmanager.proposal.domain.GeneratedProposal;
import com.bloom.eventmanager.proposal.domain.ProposalSlide;
import com.bloom.eventmanager.proposal.pdf.ProposalPdfRenderer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ProposalController.class)
class ProposalControllerTest {

    private static final String SLIDES_BODY = "{\"companyName\": \"Acme\", \"slides\": [{\"slideNumber\": 1, \"type\": \"cover\", \"title\": \"Acme\", \"content\": {\"companyName\": \"Acme\"}}]}";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private GenerateProposalUseCase generate;

    @MockitoBean
    private ProposalPdfRenderer renderer;

    @MockitoBean
    private StorageClient storage;

    @MockitoBean
    private ChatClient chat;

    @Test
    void listsCompaniesEnrichedWithChatCounts() throws Exception {
        when(storage.companies()).thenReturn(List.of(new CompanyFolder("f1", "Acme", 2)));
        when(chat.countMentioning("Acme")).thenReturn(new CountResponse(3));

        mockMvc.perform(get("/api/v1/proposals/companies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.companies[0].id").value("f1"))
                .andExpect(jsonPath("$.companies[0].briefCount").value(2))
                .andExpect(jsonPath("$.companies[0].chatCount").value(3));
    }

    @Test
    void companiesWithoutDriveAuthIs401WithReauthUrl() throws Exception {
        when(storage.companies()).thenThrow(new FeignException.Unauthorized("401",
                Request.create(Request.HttpMethod.GET, "/x", Map.of(), null, null, null), null, Map.of()));

        mockMvc.perform(get("/api/v1/proposals/companies"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("DRIVE_NOT_AUTHENTICATED"))
                .andExpect(jsonPath("$.authUrl").value("/api/v1/rag/sync/auth?force=true"));
    }

    @Test
    void generatesAProposal() throws Exception {
        when(generate.execute("Acme")).thenReturn(new GeneratedProposal(
                List.of(new ProposalSlide(1, "cover", "Acme", "Event Proposal", Map.of("companyName", "Acme"))), "Acme", "2026-09-15T10:00:00Z"));

        mockMvc.perform(post("/api/v1/proposals/generate").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"companyName\": \"Acme\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.companyName").value("Acme"))
                .andExpect(jsonPath("$.slides[0].type").value("cover"))
                .andExpect(jsonPath("$.generatedAt").value("2026-09-15T10:00:00Z"));
    }

    @Test
    void savesToDriveWithV1sFileName() throws Exception {
        when(renderer.render(any(), eq("Acme"))).thenReturn(new byte[] { 5 });
        when(storage.uploadCompanyFile(argThat(r -> r != null && r.companyName().equals("Acme")
                && r.fileName().matches("Acme_Proposal_\\d{4}-\\d{2}-\\d{2}\\.pdf") && r.contentBase64().equals("BQ=="))))
                .thenReturn(new CompanyFileResponse("folder", "file", "link"));

        mockMvc.perform(post("/api/v1/proposals/save").contentType(MediaType.APPLICATION_JSON).content(SLIDES_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.fileName").value(org.hamcrest.Matchers.matchesPattern("Acme_Proposal_\\d{4}-\\d{2}-\\d{2}\\.pdf")))
                .andExpect(jsonPath("$.fileId").value("file"))
                .andExpect(jsonPath("$.viewLink").value("link"));
    }

    @Test
    void saveAndDownloadRequireSlidesAndCompany() throws Exception {
        mockMvc.perform(post("/api/v1/proposals/save").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"companyName\": \"Acme\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("slides and companyName are required"));
        mockMvc.perform(post("/api/v1/proposals/download").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"slides\": []}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void downloadsThePdfAsAnAttachment() throws Exception {
        when(renderer.render(any(), eq("Acme"))).thenReturn("%PDF".getBytes());

        mockMvc.perform(post("/api/v1/proposals/download").contentType(MediaType.APPLICATION_JSON).content(SLIDES_BODY))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"Acme_Proposal.pdf\""))
                .andExpect(content().bytes("%PDF".getBytes()));
    }
}
```

`EventIntakeControllerTest.java`:

```java
package com.bloom.eventmanager.proposal.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.bloom.eventmanager.proposal.domain.DriveNotAuthenticatedException;
import com.bloom.eventmanager.proposal.domain.IntakeSubmission;
import com.bloom.eventmanager.proposal.domain.SubmitIntakeUseCase;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(EventIntakeController.class)
class EventIntakeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SubmitIntakeUseCase useCase;

    @Test
    void submitsTheBriefAndReturnsV1sShape() throws Exception {
        when(useCase.execute(argThat(d -> d != null && "Acme".equals(d.companyName()) && d.primaryGoals().contains("Bonding"))))
                .thenReturn(new IntakeSubmission(true, "Brief submitted successfully and uploaded to Google Drive",
                        "Acme", "Acme", "Customer_Brief_2026-09-15T10-00-00.pdf", "folder", "file", "link"));

        mockMvc.perform(post("/api/v1/event-intake").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"companyName\": \"Acme\", \"primaryGoals\": [\"Bonding\"], \"unknownField\": 1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.fileName").value("Customer_Brief_2026-09-15T10-00-00.pdf"))
                .andExpect(jsonPath("$.viewLink").value("link"));
    }

    @Test
    void driveNotAuthenticatedIs401WithAuthUrl() throws Exception {
        when(useCase.execute(any())).thenThrow(new DriveNotAuthenticatedException());

        mockMvc.perform(post("/api/v1/event-intake").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"companyName\": \"Acme\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Google Drive not authenticated."))
                .andExpect(jsonPath("$.authUrl").value("/api/v1/rag/sync/auth?force=true"));
    }
}
```

Append to the gateway's `GatewayRouteDefinitionTest`:

```java
    @Test
    void routesChatAndProposalSurfaces() {
        List<String> ids = routeLocator.getRoutes().map(Route::getId).collectList().block();

        assertThat(ids).contains("chat", "proposals", "generate-proposal", "event-intake");
        assertThat(routeLocator.getRoutes().filter(r -> "chat".equals(r.getId())).blockFirst().getUri())
                .hasToString("lb://chat-service");
        assertThat(routeLocator.getRoutes().filter(r -> "event-intake".equals(r.getId())).blockFirst().getUri())
                .hasToString("lb://proposal-service");
    }
```

- [ ] **Step 2: Run to verify failure**

Run: `./mvnw -q -pl services/proposal-service test -Dtest='ProposalControllerTest,EventIntakeControllerTest'`
Expected: COMPILATION FAILURE — `cannot find symbol: class ProposalController`

- [ ] **Step 3: Implement**

`api/dto/GenerateProposalRequest.java`, `SaveProposalRequest.java`:

```java
package com.bloom.eventmanager.proposal.api.dto;

public record GenerateProposalRequest(String companyName) {
}
```

```java
package com.bloom.eventmanager.proposal.api.dto;

import java.util.List;

import com.bloom.eventmanager.proposal.domain.ProposalSlide;

public record SaveProposalRequest(List<ProposalSlide> slides, String companyName) {

    public boolean isComplete() {
        return slides != null && !slides.isEmpty() && companyName != null && !companyName.isBlank();
    }
}
```

`api/ProposalController.java`:

```java
package com.bloom.eventmanager.proposal.api;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bloom.eventmanager.common.error.BloomException;
import com.bloom.eventmanager.common.error.ErrorCode;
import com.bloom.eventmanager.proposal.api.dto.GenerateProposalRequest;
import com.bloom.eventmanager.proposal.api.dto.SaveProposalRequest;
import com.bloom.eventmanager.proposal.client.ChatClient;
import com.bloom.eventmanager.proposal.client.StorageClient;
import com.bloom.eventmanager.proposal.client.dto.CompanyFileRequest;
import com.bloom.eventmanager.proposal.client.dto.CompanyFileResponse;
import com.bloom.eventmanager.proposal.client.dto.CompanyFolder;
import com.bloom.eventmanager.proposal.domain.GenerateProposalUseCase;
import com.bloom.eventmanager.proposal.domain.GeneratedProposal;
import com.bloom.eventmanager.proposal.domain.StorageErrors;
import com.bloom.eventmanager.proposal.pdf.ProposalPdfRenderer;

/** V1's proposalController. */
@RestController
@RequestMapping("/api/v1/proposals")
public class ProposalController {

    private final GenerateProposalUseCase generate;
    private final ProposalPdfRenderer renderer;
    private final StorageClient storage;
    private final ChatClient chat;

    public ProposalController(GenerateProposalUseCase generate, ProposalPdfRenderer renderer, StorageClient storage, ChatClient chat) {
        this.generate = generate;
        this.renderer = renderer;
        this.storage = storage;
        this.chat = chat;
    }

    @GetMapping("/companies")
    public Map<String, Object> companies() {
        List<CompanyFolder> folders;
        try {
            folders = storage.companies();
        }
        catch (RuntimeException ex) {
            throw StorageErrors.translate(ex);
        }
        List<Map<String, Object>> enriched = new ArrayList<>();
        for (CompanyFolder folder : folders) {
            long chatCount;
            try {
                chatCount = chat.countMentioning(folder.name()).count();
            }
            catch (RuntimeException ex) {
                chatCount = 0; // chat history is an enrichment, not a dependency
            }
            Map<String, Object> company = new LinkedHashMap<>();
            company.put("id", folder.id());
            company.put("name", folder.name());
            company.put("briefCount", folder.briefCount());
            company.put("chatCount", chatCount);
            enriched.add(company);
        }
        return Map.of("companies", enriched);
    }

    @PostMapping("/generate")
    public GeneratedProposal generate(@RequestBody GenerateProposalRequest request) {
        return generate.execute(request.companyName());
    }

    @PostMapping("/save")
    public Map<String, Object> save(@RequestBody SaveProposalRequest request) {
        requireComplete(request);
        byte[] pdf = renderer.render(request.slides(), request.companyName());
        String fileName = request.companyName() + "_Proposal_" + LocalDate.now(ZoneId.systemDefault()) + ".pdf";
        CompanyFileResponse upload;
        try {
            upload = storage.uploadCompanyFile(new CompanyFileRequest(request.companyName(), fileName, "application/pdf",
                    Base64.getEncoder().encodeToString(pdf)));
        }
        catch (RuntimeException ex) {
            throw StorageErrors.translate(ex);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("fileName", fileName);
        body.put("fileId", upload.fileId());
        body.put("viewLink", upload.webViewLink());
        return body;
    }

    @PostMapping(value = "/download", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> download(@RequestBody SaveProposalRequest request) {
        requireComplete(request);
        byte[] pdf = renderer.render(request.slides(), request.companyName());
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header("Content-Disposition", "attachment; filename=\"" + request.companyName() + "_Proposal.pdf\"")
                .contentLength(pdf.length)
                .body(pdf);
    }

    private static void requireComplete(SaveProposalRequest request) {
        if (request == null || !request.isComplete()) {
            throw new BloomException(ErrorCode.VALIDATION_FAILED, "slides and companyName are required");
        }
    }
}
```

`api/EventIntakeController.java`:

```java
package com.bloom.eventmanager.proposal.api;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.bloom.eventmanager.proposal.domain.EventIntakeData;
import com.bloom.eventmanager.proposal.domain.IntakeSubmission;
import com.bloom.eventmanager.proposal.domain.SubmitIntakeUseCase;

@RestController
public class EventIntakeController {

    private final SubmitIntakeUseCase useCase;

    public EventIntakeController(SubmitIntakeUseCase useCase) {
        this.useCase = useCase;
    }

    @PostMapping("/api/v1/event-intake")
    public IntakeSubmission submit(@RequestBody EventIntakeData data) {
        return useCase.execute(data);
    }
}
```

`config-repo/proposal-service.yml`:

```yaml
server:
  port: 8087
```

Gateway — add after the `ingest` route in `infrastructure/api-gateway/src/main/resources/application.yml`:

```yaml
            - id: chat
              uri: lb://chat-service
              predicates:
                - Path=/api/v1/rag/chat,/api/v1/rag/chat/**
              filters:
                - RewritePath=/api/v1/rag/chat(?<rest>.*),/api/v1/chat$\{rest}
            - id: proposals
              uri: lb://proposal-service
              predicates:
                - Path=/api/v1/rag/proposals/**
              filters:
                - RewritePath=/api/v1/rag/proposals(?<rest>.*),/api/v1/proposals$\{rest}
            - id: generate-proposal
              uri: lb://proposal-service
              predicates:
                - Path=/api/v1/rag/generate-proposal
              filters:
                - RewritePath=/api/v1/rag/generate-proposal,/api/v1/proposals/generate
            - id: event-intake
              uri: lb://proposal-service
              predicates:
                - Path=/api/v1/rag/event-intake
              filters:
                - RewritePath=/api/v1/rag/event-intake,/api/v1/event-intake
```

- [ ] **Step 4: Run every proposal-service and gateway test**

Run: `./mvnw -q -pl services/proposal-service,infrastructure/api-gateway test`
Expected: PASS — proposal 8 + 2 + 2 + 3 + 6 + 2 = 23; gateway 8

- [ ] **Step 5: Commit**

```bash
git add services/proposal-service config-repo/proposal-service.yml infrastructure/api-gateway
git commit -m "feat(proposal): companies, generate/save/download, event intake; gateway routes"
```

---

### Task 8: End-to-end — full feature parity through the gateway

**Files:** none.

- [ ] **Step 1: Build and start**

Run: `./mvnw clean install` — fifteen reactor projects. Start infra, discovery, config, knowledge, ai, retrieval, ingestion, storage, chat, proposal, gateway (environment as in Phase 3b; Drive already authenticated). Eureka lists `CHAT-SERVICE` and `PROPOSAL-SERVICE`.

- [ ] **Step 2: Event Advisor with thoughts and history**

```bash
curl -s -X POST http://localhost:8080/api/v1/rag/chat -H "Content-Type: application/json" \
     -d '{"message": "Plan a team building afternoon for 40 people who love puzzles"}' | tee chat1.json | jq '{sessionId, sources, hasThoughts: (.thoughts != null)}'
SID=$(jq -r .sessionId chat1.json)
curl -s -X POST http://localhost:8080/api/v1/rag/chat -H "Content-Type: application/json" \
     -d "{\"message\": \"Give me three completely different activities\", \"sessionId\": \"$SID\"}" | jq -r .answer | head -20
```

Expected: `status: success`, a `session_<epoch>_<7 chars>` id, non-empty `sources`, and a second answer that does not repeat the first's activities (the anti-repetition block engaged because the first answer exceeded 100 chars). `curl -s http://localhost:8080/api/v1/rag/chat/sessions | jq '.sessions[0]'` shows the session with **one** message in `messages`.

- [ ] **Step 3: Chat PDF and Drive backup**

```bash
curl -s -X POST "http://localhost:8080/api/v1/rag/chat/sessions/$SID/pdf" -o chat.pdf && file chat.pdf
curl -s -X POST "http://localhost:8080/api/v1/rag/chat/sessions/$SID/backup" | jq
```

Expected: `chat.pdf: PDF document` that opens with the "Chat Session Log" header, role boxes and a Sources list; the backup response has `drive.webViewLink`, and Drive now contains `Chats/Session_<timestamp>/Chat_Backup_session_.pdf`.

Send an Arabic message first (`"message": "خطط لي فعالية بناء فريق في القاهرة"`) and export again: Arabic lines are drawn right-aligned in Amiri with joined letter forms, English fragments left-to-right.

- [ ] **Step 4: Companies, proposal generation, save, download**

```bash
curl -s http://localhost:8080/api/v1/rag/proposals/companies | jq
COMPANY=$(curl -s http://localhost:8080/api/v1/rag/proposals/companies | jq -r '.companies[0].name')
curl -s -X POST http://localhost:8080/api/v1/rag/generate-proposal -H "Content-Type: application/json" \
     -d "{\"companyName\": \"$COMPANY\"}" | tee proposal.json | jq '.slides | map(.type)'
curl -s -X POST http://localhost:8080/api/v1/rag/proposals/save -H "Content-Type: application/json" -d @proposal.json | jq
curl -s -X POST http://localhost:8080/api/v1/rag/proposals/download -H "Content-Type: application/json" -d @proposal.json -o proposal.pdf && file proposal.pdf
```

Expected: companies carry `briefCount` and `chatCount`; nine slide types in V1's order with slide 1 `cover`; save returns `viewLink` and the PDF appears in the company's Drive folder as `<Company>_Proposal_<date>.pdf`; the downloaded file has ten or fewer 1280×720 pages in the neo-brutalist palette.

- [ ] **Step 5: Event intake brief**

```bash
curl -s -X POST http://localhost:8080/api/v1/rag/event-intake -H "Content-Type: application/json" \
     -d '{"companyName": "Parity Test Co", "contactPerson": "Jane", "targetEventDates": "June 2026", "primaryGoals": ["Bonding"], "eventDuration": "full-day", "energyPacing": "balanced", "venueType": "hybrid", "hasTheatre": "yes", "hasSoundSystem": "not-sure", "lunchBreak": "1hour", "giveaways": "yes", "giveawaysDetails": "Tote bags", "additionalContext": "Budget 50k"}' | jq
```

Expected: `success: true`, `folderName: "Parity Test Co"`, a `Customer_Brief_<stamp>.pdf` in a new `Parity Test Co` Drive folder. Within one sync interval `ingestion-service` picks the brief up (it is a PDF in the Drive tree) and `GET /api/v1/rag/proposals/companies` shows `briefCount: 1` for it — the same loop V1 closed.

- [ ] **Step 6: Drive-unauthenticated behaviour**

Clear the token (`docker exec bloom-v2-mongo mongosh tokendb --eval 'db.drivetokens.deleteMany({})'`), restart `storage-service`, then:

```bash
curl -s -i http://localhost:8080/api/v1/rag/proposals/companies | head -1
curl -s http://localhost:8080/api/v1/rag/proposals/companies | jq '{errorCode, authUrl, error}'
```

Expected: `HTTP/1.1 401`, `errorCode: DRIVE_NOT_AUTHENTICATED`, `authUrl: /api/v1/rag/sync/auth?force=true`, `error: "Google Drive not authenticated."` — the fields the React app's Drive handling reads. Re-authenticate as in Phase 3b.

- [ ] **Step 7: Record and commit**

```bash
git add README.md
git commit -m "docs: Phase 4 chat and proposals verified end to end"
```

---

## Phase 4 Exit Criteria — Full Feature Parity with V1

- [ ] `./mvnw clean install` green, fifteen reactor projects
- [ ] Every V1 endpoint under `/api/v1/rag/**` answers through the gateway with V1's response shape: `chat`, `chat/sessions`, `chat/sessions/{id}`, `chat/sessions/{id}/backup`, `retrieve`, `ingest`, `sync/**`, `proposals/companies`, `generate-proposal`, `proposals/save`, `proposals/download`, `event-intake`
- [ ] Chat history influences the next answer; the anti-repetition block appears after the first substantive answer
- [ ] Chat PDF renders mixed Arabic/English correctly via ICU4J; backup lands under `Chats/Session_…/` in Drive
- [ ] Proposal generation, save and download work against a real company folder; the event-intake brief round-trips through Drive sync into `briefCount`
- [ ] A V2 pointed at V1's Mongo database (`MONGO_CHAT_DB`) lists V1's existing sessions
- [ ] Drive-unauthenticated responses carry `authUrl` for the SPA

## What Phase 4 Does Not Do

- No frontend — every check above is `curl`; the React app moves over in Phase 5
- No JWT — the gateway is still open on localhost
- No circuit breakers or tracing — Phase 5 wraps the Feign calls built here
- The Bloom logo is not drawn on the brief header; it arrives with the frontend assets in Phase 5
