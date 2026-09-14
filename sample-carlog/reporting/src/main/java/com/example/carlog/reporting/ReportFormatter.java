package com.example.carlog.reporting;

import java.time.LocalDate;
import org.apache.commons.lang3.StringUtils;

/**
 * Formats the console output produced by the car maintenance library.
 *
 * <p>Lives in the {@code :reporting} subproject purely so that this sample build has a
 * second project to depend on. All formatting goes through Apache Commons Lang so the
 * dependency is genuinely exercised rather than declared and ignored.
 */
public final class ReportFormatter {

    private static final int LINE_WIDTH = 60;

    private ReportFormatter() {
    }

    /** Renders a centred banner, e.g. {@code "=== Honda ==="} padded to the full width. */
    public static String banner(String title) {
        return StringUtils.center(" " + StringUtils.upperCase(title) + " ", LINE_WIDTH, '=');
    }

    /**
     * Renders a single maintenance action, e.g.
     * {@code "[Honda] Rotate the tires .......... 2026-08-17 (3 total)"}.
     */
    public static String action(String carName, String description, LocalDate when, int total) {
        String prefix = "[" + carName + "] " + StringUtils.capitalize(description) + " ";
        String suffix = " " + when + " (" + total + " total)";
        int dots = Math.max(1, LINE_WIDTH - prefix.length() - suffix.length());
        return prefix + StringUtils.repeat('.', dots) + suffix;
    }

    /** Renders the answer to a "when was this last done?" query. */
    public static String lastPerformed(String carName, String description, LocalDate when) {
        if (when == null) {
            return "[" + carName + "] " + StringUtils.capitalize(description) + ": never";
        }
        return "[" + carName + "] " + StringUtils.capitalize(description) + ": " + when;
    }
}
