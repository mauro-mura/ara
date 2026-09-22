package io.ara.examples.support;

/**
 * Demo plumbing for the live examples: how a run opts into a real endpoint, and where the
 * API key comes from.
 *
 * <p>Four examples used to roll their own copy of this. The precedence is hard-coded ARA
 * convention: {@code -Dara.api.key=...} beats {@code ARA_API_KEY}, and local gateways that
 * ignore the key fall back to a placeholder default.
 */
public final class Live {

    /**
     * Whether {@code live} was requested on the command line: the first argument spelled
     * {@code live} (case-insensitive) or {@code -Dara.example.live=true}.
     */
    public static boolean requested(String[] args) {
        return Boolean.getBoolean("ara.example.live")
                || (args.length > 0 && args[0].equalsIgnoreCase("live"));
    }

    /** First non-blank of {@code -Dara.api.key}, {@code ARA_API_KEY}, then {@code fallback}. */
    public static String apiKey(String fallback) {
        String property = System.getProperty("ara.api.key");
        if (property != null && !property.isBlank()) return property;
        String env = System.getenv("ARA_API_KEY");
        if (env != null && !env.isBlank()) return env;
        return fallback;
    }

    /**
     * Like {@link #apiKey(String)} but fails fast when no key was configured — for runs that
     * have no usable default, where a "not-required" placeholder would mislead more than help.
     */
    public static String apiKeyRequired() {
        String key = apiKey("");
        if (key.isBlank()) {
            throw new IllegalStateException(
                    "Missing API key: set -Dara.api.key=... or the ARA_API_KEY environment variable");
        }
        return key;
    }

    private Live() { }
}
