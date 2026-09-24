package com.example.evidencechain.support;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Deterministic hashing/canonicalization used by the evidence chain.
 *
 * <p>Rules:
 * <ul>
 *   <li>SHA-256, lowercase hex, 64 chars.</li>
 *   <li>Payload JSON is serialized with sorted map keys and no pretty print,
 *       so the same logical payload always yields the same bytes regardless
 *       of insertion order.</li>
 *   <li>Timestamps are formatted ISO-8601 in UTC with millisecond precision
 *       ({@code 2026-09-24T10:00:00.123Z}).</li>
 * </ul>
 */
public final class Hashing {

    /** Thread-safe mapper configured to sort properties alphabetically. */
    public static final ObjectMapper CANONICAL_MAPPER = new ObjectMapper()
            .configure(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
            .withZone(ZoneOffset.UTC);

    private Hashing() {
    }

    public static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public static String formatInstant(Instant instant) {
        return TS.format(instant);
    }

    /**
     * Canonical JSON for an arbitrary object (typically a {@code Map}).
     * Map entries are emitted in key order; nulls are kept.
     */
    public static String canonicalJson(Object payload) {
        try {
            Object sorted = CANONICAL_MAPPER.convertValue(payload, Object.class);
            return CANONICAL_MAPPER.writeValueAsString(sorted);
        } catch (Exception e) {
            throw new IllegalArgumentException("Payload cannot be canonicalized: " + e.getMessage(), e);
        }
    }

    /**
     * Builds the exact string that is hashed into an event's eventHash.
     * Fields are separated by a newline; a field can never inject a
     * separator-ambiguous value because it sits alone on its line.
     */
    public static String buildEventCanonical(int seq,
                                             long planId,
                                             String eventType,
                                             String fromStatus,
                                             String toStatus,
                                             String prevHash,
                                             Instant eventTime,
                                             String payloadHash) {
        return String.join("\n",
                Integer.toString(seq),
                Long.toString(planId),
                eventType,
                fromStatus == null ? "" : fromStatus,
                toStatus,
                prevHash,
                formatInstant(eventTime),
                payloadHash);
    }
}
