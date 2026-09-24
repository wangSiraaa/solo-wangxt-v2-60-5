package com.example.evidence.chain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.TreeMap;

/**
 * Canonical serialization and SHA-256 hashing for evidence events.
 *
 * <p>The canonical form is fully deterministic: JSON object keys are sorted
 * lexicographically (recursively), no insignificant whitespace, UTF-8. Both
 * the writer (append) and the reader (verify) use the same algorithm so that a
 * stored hash can be recomputed independently from the row's columns.
 */
@Component
public class HashChain {

    /** Previous-hash of the first (genesis) event of every plan. */
    public static final String GENESIS_PREV_HASH = "0".repeat(64);

    private static final ObjectMapper CANONICAL_MAPPER = new ObjectMapper();

    /** Deterministic canonical JSON for the supplied map (keys sorted recursively). */
    public String canonicalJson(Map<String, Object> payload) {
        return canonicalJsonStatic(payload);
    }

    public static String canonicalJsonStatic(Map<String, Object> payload) {
        try {
            return CANONICAL_MAPPER.writeValueAsString(sort(payload));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("payload cannot be serialized to canonical JSON", e);
        }
    }

    /**
     * Hash input layout (fields separated by newline):
     * <pre>
     *   planId
     *   seq
     *   eventType
     *   stateBefore        ("" when null, e.g. genesis)
     *   stateAfter
     *   prevHash
     *   epochMillis
     *   canonical payload JSON
     * </pre>
     */
    public String computeHash(HashInput input) {
        return computeHashStatic(input);
    }

    public static String computeHashStatic(HashInput input) {
        String stateBefore = input.stateBefore() == null ? "" : input.stateBefore();
        String canonical = canonicalJsonStatic(input.payload());
        String material = String.join("\n",
                String.valueOf(input.planId()),
                String.valueOf(input.seq()),
                input.eventType(),
                stateBefore,
                input.stateAfter(),
                input.prevHash(),
                String.valueOf(input.epochMillis()),
                canonical);
        return sha256Hex(material);
    }

    public static boolean isGenesisHash(String hash) {
        return GENESIS_PREV_HASH.equals(hash);
    }

    public static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Object sort(Object value) {
        if (value instanceof Map<?, ?> map) {
            TreeMap<String, Object> sorted = new TreeMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                sorted.put(String.valueOf(e.getKey()), sort(e.getValue()));
            }
            return sorted;
        }
        if (value instanceof Iterable<?> iterable) {
            java.util.List<Object> out = new java.util.ArrayList<>();
            for (Object o : iterable) {
                out.add(sort(o));
            }
            return out;
        }
        return value;
    }

    /** Immutable description of everything that goes into an event hash. */
    public record HashInput(
            long planId,
            long seq,
            String eventType,
            String stateBefore,
            String stateAfter,
            String prevHash,
            long epochMillis,
            Map<String, Object> payload) {
    }
}
