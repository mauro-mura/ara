package io.ara.core.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * SHA-256 as lowercase hex — the content-addressing primitive shared across the
 * meta-agent backlog: the {@code AgentConfig} behavioural hash ({@code SpecLineage},
 * ADR-0065) and content-addressed blob refs for trace payloads ({@code BlobStore},
 * ADR-0068). Extracted so callers do not each carry their own copy of the digest-and-hex
 * loop. Kept in {@code io.ara.core.internal} — not public API.
 */
public final class Sha256 {

    private Sha256() {}

    /** Lowercase hex SHA-256 of {@code content}. */
    public static String hex(byte[] content) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);   // never on a compliant JRE
        }
        byte[] hashed = digest.digest(content);
        return HexFormat.of().formatHex(hashed);
    }

    /** Lowercase hex SHA-256 of {@code text}, UTF-8 encoded. */
    public static String hexUtf8(String text) {
        return hex(text.getBytes(StandardCharsets.UTF_8));
    }
}
