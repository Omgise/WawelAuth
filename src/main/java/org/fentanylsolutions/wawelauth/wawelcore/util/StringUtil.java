package org.fentanylsolutions.wawelauth.wawelcore.util;

/**
 * Shared string utilities used across the WawelAuth codebase.
 */
public final class StringUtil {

    private StringUtil() {}

    /**
     * Trims whitespace and returns null for empty/blank strings.
     *
     * @return the trimmed string, or null if blank/null
     */
    public static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * Strips trailing slashes from a URL string.
     * Returns null for blank/null input.
     */
    public static String stripTrailingSlashes(String raw) {
        String value = trimToNull(raw);
        if (value == null) return null;
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value.isEmpty() ? null : value;
    }

    /**
     * Normalizes an HTTP(S) URL: trims, prepends {@code https://} if no scheme
     * is present, and strips trailing slashes.
     *
     * @return the normalized URL, or null for blank/null input
     */
    public static String normalizeHttpUrl(String raw) {
        String value = trimToNull(raw);
        if (value == null) return null;
        if (!value.startsWith("http://") && !value.startsWith("https://")) {
            value = "https://" + value;
        }
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value.isEmpty() ? null : value;
    }
}
