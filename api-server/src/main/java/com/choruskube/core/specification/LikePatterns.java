package com.choruskube.core.specification;

/** Helpers for building SQL {@code LIKE} patterns from user input. */
public final class LikePatterns {

    private LikePatterns() {}

    /**
     * Escapes SQL LIKE wildcard characters (%, _) and the escape character itself (\) so that
     * user-provided search terms are treated as literal strings, not patterns.
     */
    public static String escapeLikePattern(String input) {
        return input.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /** Builds a case-insensitive {@code %term%} contains-pattern with wildcards escaped. */
    public static String containsIgnoreCase(String input) {
        return "%" + escapeLikePattern(input.toLowerCase()) + "%";
    }
}
