package com.bloom.eventmanager.ai.gemini;

import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Classifies Gemini failures the way V1 did: a 429 (quota) or a 404 (model
 * does not exist) means "try the next model in the chain"; anything else is a
 * genuine failure to report.
 *
 * <p>The SDK's exception messages start with the HTTP status ("429
 * RESOURCE_EXHAUSTED: …"). Reading it from the message, through the cause
 * chain, keeps this independent of which wrapper Spring AI's retry layer
 * happens to throw.
 */
final class GeminiErrors {

    private static final Pattern LEADING_STATUS = Pattern.compile("(?<!\\d)([45]\\d\\d)(?!\\d)");

    private GeminiErrors() {
    }

    static OptionalInt statusCode(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            String message = current.getMessage();
            if (message == null) {
                continue;
            }
            Matcher matcher = LEADING_STATUS.matcher(message);
            if (matcher.find()) {
                return OptionalInt.of(Integer.parseInt(matcher.group(1)));
            }
        }
        return OptionalInt.empty();
    }

    static boolean shouldTryNextModel(Throwable failure) {
        OptionalInt code = statusCode(failure);
        return code.isPresent() && (code.getAsInt() == 429 || code.getAsInt() == 404);
    }
}
