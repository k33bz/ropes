package com.k33bz.ropes;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The game-free rules behind the 0.3.1 store, command and cleanup fixes. */
class RopeChecksTest {

    @Test
    void wellFormedNeedsADimensionAndTwoPosts() {
        int[] p = {1, 64, 1};
        assertTrue(RopeChecks.wellFormed("minecraft:overworld", p, new int[] {1, 70, 1}));
        // Each of these used to throw inside the server tick and crash the server on every boot
        assertFalse(RopeChecks.wellFormed(null, p, p));
        assertFalse(RopeChecks.wellFormed("  ", p, p));
        assertFalse(RopeChecks.wellFormed("minecraft:overworld", null, p));
        assertFalse(RopeChecks.wellFormed("minecraft:overworld", p, new int[] {1, 2}));
    }

    @Test
    void parseUuidNeverThrows() {
        UUID id = UUID.randomUUID();
        assertEquals(id, RopeChecks.parseUuid(id.toString()));
        assertNull(RopeChecks.parseUuid(null));          // used to be an uncaught NullPointerException
        assertNull(RopeChecks.parseUuid("not-a-uuid"));
    }

    @Test
    void reachIsMeasuredToTheBlockCentre() {
        // Eye at (0.5, 1.62, 0.5): a post 5 blocks east is in reach of 6, one 7 blocks east is not
        assertTrue(RopeChecks.withinReach(0.5, 1.62, 0.5, 5, 1, 0, 6.0));
        assertFalse(RopeChecks.withinReach(0.5, 1.62, 0.5, 7, 1, 0, 6.0));
        // The old /rope tie worked from anywhere; a post a thousand blocks off is now refused
        assertFalse(RopeChecks.withinReach(0.5, 1.62, 0.5, 1000, 64, 0, 6.0));
    }

    @Test
    void knotCapSegIdComesFromItsPerSegmentTag() {
        assertEquals("abc123", RopeChecks.segIdOfKnotTags(List.of("ropes_knot", "ropes_knot_abc123")));
        assertNull(RopeChecks.segIdOfKnotTags(List.of("ropes_knot")));
        assertNull(RopeChecks.segIdOfKnotTags(List.of("ropes_knot_", "other")));
    }

    @Test
    void textureMustBePlainBase64() {
        String real = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvYWJjIn19fQ==";
        assertEquals(real, RopeChecks.cleanTexture(real));
        assertEquals("", RopeChecks.cleanTexture(null));
        assertEquals("", RopeChecks.cleanTexture(""));
        // A quote or brace would end the SNBT string and rewrite the console summon command
        assertEquals("", RopeChecks.cleanTexture("abc\"}]},Tags:[\"x\"]"));
    }
}
