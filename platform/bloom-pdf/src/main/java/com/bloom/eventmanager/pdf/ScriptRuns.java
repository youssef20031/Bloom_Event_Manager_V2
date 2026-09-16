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
