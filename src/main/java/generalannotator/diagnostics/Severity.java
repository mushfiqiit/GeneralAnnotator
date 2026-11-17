package generalannotator.diagnostics;

import java.util.Locale;

/** Severity of a compiler diagnostic as printed by javac. */
public enum Severity {
    ERROR,
    WARNING,
    NOTE;

    /** Parses javac's lowercase severity keyword ({@code error}, {@code warning}, {@code note}). */
    public static Severity fromJavacKeyword(String keyword) {
        return switch (keyword) {
            case "error" -> ERROR;
            case "warning" -> WARNING;
            case "note" -> NOTE;
            default -> throw new IllegalArgumentException("Unknown javac severity: " + keyword);
        };
    }

    /** Lowercase name used in reports. */
    public String label() {
        return name().toLowerCase(Locale.ROOT);
    }
}
