package com.example.evidence.chain;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Determinism / malleability properties of the canonical hash function. */
class HashChainTest {

    private final HashChain hashChain = new HashChain();

    @Test
    void genesisConstantIs64Zeros() {
        assertThat(HashChain.GENESIS_PREV_HASH).hasSize(64).matches("0{64}");
    }

    @Test
    void hashIsStableAcrossInsertionOrderOfMapKeys() {
        Map<String, Object> a = new java.util.LinkedHashMap<>();
        a.put("operator", "alice");
        a.put("shift", "night");
        a.put("nested", new java.util.TreeMap<>(Map.of("b", 2, "a", 1)));

        Map<String, Object> b = new java.util.LinkedHashMap<>();
        b.put("nested", new java.util.TreeMap<>(Map.of("a", 1, "b", 2)));
        b.put("shift", "night");
        b.put("operator", "alice");

        HashChain.HashInput in1 = input(a);
        HashChain.HashInput in2 = input(b);
        assertThat(hashChain.computeHash(in2)).isEqualTo(hashChain.computeHash(in1));
    }

    @Test
    void anyFieldChangeChangesHash() {
        Map<String, Object> payload = Map.of("k", "v");
        String base = hashChain.computeHash(input(payload));

        assertThat(hashChain.computeHash(new HashChain.HashInput(
                99L, 1, "STARTED", null, "ISSUED", HashChain.GENESIS_PREV_HASH,
                1000L, payload))).isNotEqualTo(base);
        assertThat(hashChain.computeHash(new HashChain.HashInput(
                1L, 2, "STARTED", null, "ISSUED", HashChain.GENESIS_PREV_HASH,
                1000L, payload))).isNotEqualTo(base);
        assertThat(hashChain.computeHash(new HashChain.HashInput(
                1L, 1, "CONFIRMED", null, "ISSUED", HashChain.GENESIS_PREV_HASH,
                1000L, payload))).isNotEqualTo(base);
        assertThat(hashChain.computeHash(new HashChain.HashInput(
                1L, 1, "STARTED", "DRAFT", "ISSUED", HashChain.GENESIS_PREV_HASH,
                1000L, payload))).isNotEqualTo(base);
        assertThat(hashChain.computeHash(new HashChain.HashInput(
                1L, 1, "STARTED", null, "DRAFT", HashChain.GENESIS_PREV_HASH,
                1000L, payload))).isNotEqualTo(base);
        assertThat(hashChain.computeHash(new HashChain.HashInput(
                1L, 1, "STARTED", null, "ISSUED", "a".repeat(64), 1000L, payload)))
                .isNotEqualTo(base);
        assertThat(hashChain.computeHash(new HashChain.HashInput(
                1L, 1, "STARTED", null, "ISSUED", HashChain.GENESIS_PREV_HASH,
                1001L, payload))).isNotEqualTo(base);
        assertThat(hashChain.computeHash(input(Map.of("k", "w")))).isNotEqualTo(base);
    }

    @Test
    void listOrderIsSignificant() {
        Map<String, Object> p1 = Map.of("items", List.of("a", "b"));
        Map<String, Object> p2 = Map.of("items", List.of("b", "a"));
        assertThat(hashChain.computeHash(input(p1))).isNotEqualTo(hashChain.computeHash(input(p2)));
    }

    @Test
    void genesisEventHasNullStateBeforeSerializedAsEmpty() {
        String hash = hashChain.computeHash(new HashChain.HashInput(
                1L, 1, "CREATED", null, "DRAFT", HashChain.GENESIS_PREV_HASH,
                1000L, Map.of("title", "t")));
        assertThat(hash).hasSize(64).matches("[0-9a-f]{64}");
        // recompute with explicit "" must match
        String hash2 = hashChain.computeHash(new HashChain.HashInput(
                1L, 1, "CREATED", "", "DRAFT", HashChain.GENESIS_PREV_HASH,
                1000L, Map.of("title", "t")));
        assertThat(hash).isEqualTo(hash2);
    }

    private HashChain.HashInput input(Map<String, Object> payload) {
        return new HashChain.HashInput(1L, 1, "STARTED", null, "ISSUED",
                HashChain.GENESIS_PREV_HASH, 1000L, payload);
    }
}
