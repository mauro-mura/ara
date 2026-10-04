package io.ara.core.spec;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.ara.core.agent.AgentConfig;
import io.ara.core.internal.Sha256;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.temporal.TemporalAmount;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Evolutionary lineage of an {@link AgentSpec}: a content-addressed hash of the wrapped
 * {@link AgentConfig}'s <em>behaviour</em>, the depth of this spec in its derivation
 * chain, its lifecycle {@link SpecStatus}, and a back-pointer to the parent's hash
 * (ADR-0065 D2).
 *
 * <p><b>What the hash covers.</b> Only the fields that define how the agent behaves:
 * {@code agentType}, {@code tags}, {@code systemPrompt}, {@code promptCatalogId}, and the
 * {@code llm} / {@code execution} / {@code memory} sub-records in full — so a future
 * field on any of those three enters the hash automatically, with no list here to keep
 * in sync. It deliberately excludes {@code agentId} (random per construction —
 * {@code AgentId.generate()}), {@code name}, {@code description} and the free-text
 * {@code version} field of {@code AgentIdentity}: two behaviourally identical configs
 * built at different moments must hash identically (ADR-0065 DR-2).
 *
 * <p><b>Canonical form.</b> The behavioural fields are projected into a sorted tree of
 * plain {@code Map}/{@code List}/{@code String}/number/boolean nodes — every record
 * expanded by reflection and tagged with its simple class name under {@code "@type"} so
 * distinct shapes (e.g. {@code Budget.Unlimited} vs {@code Budget.Limited}, or the four
 * {@code StrategyConfig} variants) never collide — then serialised with keys sorted and
 * SHA-256'd. The exact serialisation is an implementation detail; only determinism
 * matters (ADR-0065, "Non affrontato").
 */
public record SpecLineage(
        String     specHash,
        int        specVersion,
        SpecStatus status,
        String     derivedFrom
) {

    public SpecLineage {
        Objects.requireNonNull(specHash, "specHash must not be null");
        Objects.requireNonNull(status, "status must not be null");
        if (specVersion < 1) {
            throw new IllegalArgumentException("specVersion must be >= 1, got: " + specVersion);
        }
    }

    /** The lineage of a spec with no parent: version 1, {@link SpecStatus.Draft}, no {@code derivedFrom}. */
    static SpecLineage root(AgentConfig config) {
        return new SpecLineage(hash(config), 1, new SpecStatus.Draft(), null);
    }

    /**
     * The lineage of a spec derived from {@code parent} by swapping in {@code newConfig}:
     * a fresh hash, {@code parent.specVersion + 1}, back to {@link SpecStatus.Draft},
     * {@code derivedFrom} set to the parent's hash.
     */
    SpecLineage deriveFrom(AgentSpec parent, AgentConfig newConfig) {
        return new SpecLineage(
                hash(newConfig),
                parent.lineage().specVersion() + 1,
                new SpecStatus.Draft(),
                parent.lineage().specHash());
    }

    /** A copy with only {@link #status} replaced — same hash, same version (ADR-0065 D5). */
    SpecLineage withStatus(SpecStatus newStatus) {
        return new SpecLineage(specHash, specVersion, newStatus, derivedFrom);
    }

    /**
     * Rejects a config that could not be told apart from any other in an archive keyed by
     * capability (ADR-0065 D4): {@code agentType} blank, or the {@code Builder}'s
     * non-discriminating default {@code "generic"} (a fact already established in
     * ADR-052 D5 check 5).
     */
    static void validate(AgentConfig config) {
        Objects.requireNonNull(config, "config must not be null");
        String type = config.agentType();
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("AgentSpec requires a non-blank agentType");
        }
        if (type.equals("generic")) {
            throw new IllegalArgumentException(
                    "AgentSpec requires a specific agentType — \"generic\" is the AgentConfig.Builder "
                            + "default and is not a discriminator (see ADR-052 D5 check 5)");
        }
    }

    // ── content-addressed hash ────────────────────────────────────────────────

    private static final JsonMapper CANONICAL = JsonMapper.builder()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    /**
     * Content-addressed hash of a config's <em>behaviour</em> — the identity used (among
     * others) as {@code ArchiveEntry.specHash} (ADR-0082) and as the coherence check of the
     * distributed-spec pipeline (ADR-0105 Parte 3 D4: recompute from a freshly decoded
     * {@code config} and compare with the claimed {@code specHash}). Public since
     * ADR-0105; {@code AgentSpec.specHash()} is the same value materialised on a spec.
     */
    public static String hash(AgentConfig config) {
        // Each value is normalised on the way in by putIfPresent; the map is a TreeMap,
        // so the whole structure is already canonical before serialisation.
        Map<String, Object> behaviour = new TreeMap<>();
        putIfPresent(behaviour, "agentType", config.agentType());
        putIfPresent(behaviour, "tags", config.tags());
        putIfPresent(behaviour, "systemPrompt", config.systemPrompt());
        putIfPresent(behaviour, "promptCatalogId", config.promptCatalogId());
        putIfPresent(behaviour, "llm", config.llm());
        putIfPresent(behaviour, "execution", config.execution());
        putIfPresent(behaviour, "memory", config.memory());

        String canonicalJson;
        try {
            canonicalJson = CANONICAL.writeValueAsString(behaviour);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to canonicalise AgentConfig for hashing", e);
        }
        return Sha256.hexUtf8(canonicalJson);
    }

    /**
     * Short digest of the overlay axes that sit <em>outside</em> {@link #hash(AgentConfig)}
     * — few-shot refs (ADR-0066 D6), the input-schema ref (ADR-0077 D1), the output-schema ref and the repair-attempt count — used by
     * {@link AgentSpec#effectiveHash()} to give the evolution cycle a candidate id the
     * behavioural hash deliberately cannot provide. Truncated to 12 hex characters: it
     * disambiguates candidates within one lineage, it is not a security boundary.
     */
    static String overlayDigest(List<String> fewShotRefs, String schemaRef, String outputSchemaRef,
                                int outputRepairAttempts) {
        Map<String, Object> overlay = new TreeMap<>();
        overlay.put("fewShotRefs", List.copyOf(fewShotRefs));   // order-sensitive, kept as a list
        if (schemaRef != null) {
            overlay.put("schemaRef", schemaRef);
        }
        // Added only when set, so the digest of a spec with no output schema is byte-identical to
        // what it was before this key existed — every effectiveHash already in an archive keeps
        // naming the same candidate.
        if (outputSchemaRef != null) {
            overlay.put("outputSchemaRef", outputSchemaRef);
        }
        if (outputRepairAttempts != 0) {   // same rule: absent when off, so existing digests hold
            overlay.put("outputRepairAttempts", outputRepairAttempts);
        }
        try {
            return Sha256.hexUtf8(CANONICAL.writeValueAsString(overlay)).substring(0, 12);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to canonicalise overlay refs for hashing", e);
        }
    }

    private static void putIfPresent(Map<String, Object> target, String key, Object value) {
        Object normalised = canonicalise(value);
        if (normalised != null) {
            target.put(key, normalised);
        }
    }

    /**
     * Recursively projects an arbitrary value from the {@link AgentConfig} object graph
     * into plain JSON-serialisable nodes with a deterministic shape. Every record is
     * expanded to a sorted map tagged with {@code "@type"}; leaf types Jackson would
     * otherwise serialise ambiguously ({@link BigDecimal}, {@link Enum}, temporal
     * amounts) are pinned to a canonical string/number.
     */
    private static Object canonicalise(Object value) {
        switch (value) {
            case null -> {
                return null;
            }
            case String s -> {
                return s;
            }
            case Boolean b -> {
                return b;
            }
            case BigDecimal d -> {
                return d.stripTrailingZeros().toPlainString();
            }
            case Number n -> {
                return n;
            }
            case Enum<?> e -> {
                return e.name();
            }
            case TemporalAmount t -> {
                return t.toString();   // Duration/Period ISO-8601 — stable and unambiguous
            }
            case Map<?, ?> map -> {
                Map<String, Object> out = new TreeMap<>();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    Object v = canonicalise(entry.getValue());
                    if (v != null) {
                        out.put(String.valueOf(entry.getKey()), v);
                    }
                }
                return out;
            }
            case Collection<?> collection -> {
                List<Object> out = new java.util.ArrayList<>(collection.size());
                for (Object element : collection) {
                    out.add(canonicalise(element));
                }
                return out;
            }
            default -> {
                if (value.getClass().isRecord()) {
                    return canonicaliseRecord(value);
                }
                // Nothing in the AgentConfig graph is expected to reach here; fall back
                // to a stable string rather than let an unknown type break hashing.
                return value.toString();
            }
        }
    }

    private static Map<String, Object> canonicaliseRecord(Object record) {
        Map<String, Object> out = new TreeMap<>();
        out.put("@type", record.getClass().getSimpleName());
        for (RecordComponent component : record.getClass().getRecordComponents()) {
            Object componentValue;
            try {
                componentValue = component.getAccessor().invoke(record);
            } catch (IllegalAccessException | InvocationTargetException e) {
                throw new IllegalStateException(
                        "failed to read record component " + component.getName()
                                + " of " + record.getClass().getSimpleName(), e);
            }
            Object normalised = canonicalise(componentValue);
            if (normalised != null) {
                out.put(component.getName(), normalised);
            }
        }
        return out;
    }
}
