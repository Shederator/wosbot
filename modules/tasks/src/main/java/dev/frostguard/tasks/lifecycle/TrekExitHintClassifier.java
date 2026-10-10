package dev.frostguard.tasks.lifecycle;

import java.util.Locale;

/** Identifies the English result-overlay instruction that safely dismisses Trek results. */
final class TrekExitHintClassifier {

    private TrekExitHintClassifier() {
    }

    static Evidence inspect(String ocrText) {
        String normalized = normalize(ocrText);
        int tap = wordIndex(normalized, "tap", 0);
        int exit = tap < 0 ? -1 : wordIndex(normalized, "exit", tap + 3);
        return new Evidence(tap >= 0 && exit >= 0, normalized);
    }

    private static String normalize(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return text.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z]+", " ")
                .trim()
                .replaceAll("\\s+", " ");
    }

    private static int wordIndex(String text, String word, int start) {
        int index = text.indexOf(word, start);
        while (index >= 0) {
            int end = index + word.length();
            boolean beforeBoundary = index == 0 || text.charAt(index - 1) == ' ';
            boolean afterBoundary = end == text.length() || text.charAt(end) == ' ';
            if (beforeBoundary && afterBoundary) {
                return index;
            }
            index = text.indexOf(word, end);
        }
        return -1;
    }

    record Evidence(boolean detected, String normalizedText) {
    }
}
