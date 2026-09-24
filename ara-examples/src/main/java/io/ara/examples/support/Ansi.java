package io.ara.examples.support;

/**
 * Minimal ANSI styling for the console-showcase examples — the terminal counterpart of the
 * CSS the web examples ship in {@code resources/web}. It exists so a demo can colour a
 * reviewer's lines, dim a timestamp and draw a section rule without every example
 * re-deriving the escape sequences.
 *
 * <p>Colour is on by default (the whole point is a screenshot-worthy terminal) and can be
 * turned off two ways: the conventional {@code NO_COLOR} environment variable, or
 * {@code -Dara.color=never}. Both produce plain text — useful for CI logs, where raw escape
 * sequences are noise. The check is done once, at class load: a demo run is short-lived and
 * does not change its colour setting mid-run.
 *
 * <p>Immutable and thread-safe: the constants are plain strings and {@link #paint} is a pure
 * function of its arguments.
 */
public final class Ansi {

    private static final String ESC = "\u001B[";

    public static final String RESET   = ESC + "0m";
    public static final String BOLD    = ESC + "1m";
    public static final String DIM     = ESC + "2m";
    public static final String RED     = ESC + "31m";
    public static final String GREEN   = ESC + "32m";
    public static final String YELLOW  = ESC + "33m";
    public static final String BLUE    = ESC + "34m";
    public static final String MAGENTA = ESC + "35m";
    public static final String CYAN    = ESC + "36m";
    public static final String GREY    = ESC + "90m";

    private static final boolean ENABLED =
            System.getenv("NO_COLOR") == null
                    && !"never".equalsIgnoreCase(System.getProperty("ara.color", ""));

    /** Wraps {@code text} in {@code color} (a code from this class), or returns it untouched when colour is off. */
    public static String paint(String color, String text) {
        return ENABLED ? color + text + RESET : text;
    }

    /** Whether styling is currently applied — callers that align columns can skip padding math when it is off. */
    public static boolean enabled() {
        return ENABLED;
    }

    private Ansi() { }
}
