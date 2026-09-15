package com.bloom.eventmanager.common.ai;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pulls a JSON payload out of a model response that may be wrapped in prose or
 * a fenced code block, and removes the trailing commas models routinely emit.
 * A direct port of V1's {@code GeminiAIService.extractJson}.
 */
public final class JsonExtractor {

    private static final Pattern CODE_BLOCK = Pattern.compile("```(?:json)?\\s*\\n?([\\s\\S]*?)\\n?```");
    private static final Pattern TRAILING_COMMA = Pattern.compile(",(\\s*[}\\]])");

    private JsonExtractor() {
    }

    public static String extract(String text) {
        if (text == null) {
            return "";
        }

        String extracted;
        Matcher fenced = CODE_BLOCK.matcher(text);
        if (fenced.find()) {
            extracted = fenced.group(1).trim();
        }
        else {
            int firstBrace = text.indexOf('{');
            int firstBracket = text.indexOf('[');
            int lastBrace = text.lastIndexOf('}');
            int lastBracket = text.lastIndexOf(']');

            int start = -1;
            if (firstBrace != -1 && (firstBracket == -1 || firstBrace < firstBracket)) {
                start = firstBrace;
            }
            else if (firstBracket != -1) {
                start = firstBracket;
            }

            int end = -1;
            if (lastBrace != -1 && (lastBracket == -1 || lastBrace > lastBracket)) {
                end = lastBrace;
            }
            else if (lastBracket != -1) {
                end = lastBracket;
            }

            extracted = (start != -1 && end != -1 && end > start)
                    ? text.substring(start, end + 1).trim()
                    : text.trim();
        }

        return TRAILING_COMMA.matcher(extracted).replaceAll("$1");
    }
}
