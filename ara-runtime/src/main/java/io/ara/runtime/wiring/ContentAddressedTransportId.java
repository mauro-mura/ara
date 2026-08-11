package io.ara.runtime.wiring;

import io.ara.core.llm.LlmTransport;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Derives a stable, content-addressed registry id for an inline {@link LlmTransport}
 * override (set directly on an {@code LlmProfile}, instead of referencing a registered
 * {@code transportId}) — ADR-039 §3.
 *
 * <p>Hashing the API key (rather than embedding it in the id) keeps the derived id safe
 * to log or persist. Two transports with identical {@code baseUrl}+{@code modelName}+
 * {@code apiKey} resolve to the same id, so repeated inline overrides dedupe against the
 * same {@link ResourceRegistry} entry instead of each building a throwaway transport.
 */
public final class ContentAddressedTransportId {

    private static final String PREFIX = "inline:";

    private ContentAddressedTransportId() { }

    public static String forInlineTransport(LlmTransport transport) {
        String hashedApiKey = sha256Hex(transport.apiKey() == null ? "" : transport.apiKey());
        String material = transport.baseUrl() + "|" + transport.modelName() + "|" + hashedApiKey;
        return PREFIX + sha256Hex(material);
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
