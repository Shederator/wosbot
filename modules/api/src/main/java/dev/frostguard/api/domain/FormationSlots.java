package dev.frostguard.api.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

/** Shared capability for saved squad formation slots supported by Frostguard. */
public final class FormationSlots {

    public static final int MIN = 1;
    public static final int MAX = 12;

    private static final List<Integer> NUMBERS = IntStream.rangeClosed(MIN, MAX).boxed().toList();
    private static final List<String> LABELS = NUMBERS.stream().map(String::valueOf).toList();

    private FormationSlots() {
    }

    public static boolean supports(Integer slot) {
        return slot != null && slot >= MIN && slot <= MAX;
    }

    public static List<Integer> numbers() {
        return NUMBERS;
    }

    public static List<Integer> numbersWithNone(int noneValue) {
        List<Integer> options = new ArrayList<>(NUMBERS.size() + 1);
        options.add(noneValue);
        options.addAll(NUMBERS);
        return List.copyOf(options);
    }

    public static List<String> labels() {
        return LABELS;
    }

    public static List<String> labelsWithNone(String noneLabel) {
        List<String> options = new ArrayList<>(LABELS.size() + 1);
        options.add(noneLabel);
        options.addAll(LABELS);
        return List.copyOf(options);
    }

    public static Integer parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            int slot = Integer.parseInt(value.trim());
            return supports(slot) ? slot : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
