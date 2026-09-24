package com.example.evidencechain.support;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HashingTest {

    @Test
    void sha256IsDeterministicLowercase64Hex() {
        String h1 = Hashing.sha256Hex("hello");
        String h2 = Hashing.sha256Hex("hello");
        assertThat(h1).isEqualTo(h2);
        assertThat(h1).hasSize(64).matches("[0-9a-f]{64}");
        // Known SHA-256 of "hello"
        assertThat(h1).isEqualTo(
                "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824");
    }

    @Test
    void canonicalJsonOrdersKeysRegardlessOfInsertionOrder() {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("z", 1);
        a.put("a", 2);
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("a", 2);
        b.put("z", 1);

        assertThat(Hashing.canonicalJson(a)).isEqualTo(Hashing.canonicalJson(b));
        assertThat(Hashing.canonicalJson(a)).isEqualTo("{\"a\":2,\"z\":1}");
    }

    @Test
    void eventCanonicalChangesWhenAnyFieldChanges() {
        String base = Hashing.buildEventCanonical(1, 10, "START", "DRAFT", "STARTED",
                "0".repeat(64), java.time.Instant.parse("2026-09-24T10:00:00Z"),
                "a".repeat(64));
        String changedSeq = Hashing.buildEventCanonical(2, 10, "START", "DRAFT", "STARTED",
                "0".repeat(64), java.time.Instant.parse("2026-09-24T10:00:00Z"),
                "a".repeat(64));
        assertThat(Hashing.sha256Hex(base)).isNotEqualTo(Hashing.sha256Hex(changedSeq));
    }
}
