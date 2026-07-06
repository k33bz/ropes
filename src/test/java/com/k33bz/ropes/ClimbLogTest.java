package com.k33bz.ropes;

import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic tests for the climb-log NDJSON serializer and the daily-file name — no game runtime.
 * These pin the EXACT on-disk schema the mc.kast.ro stats site parses, so a change to field order,
 * number formatting, or the file name pattern fails loudly.
 */
class ClimbLogTest {

    @Test
    void schemaFieldOrderAndCompactWholeNumbers() {
        ClimbLog.Session s = new ClimbLog.Session(
                1_700_000_000_000L, "k33bz", "u-1", 12.0, 3.0, 8.0, 6.0, "minecraft:overworld");
        String json = ClimbLog.toJson(s);
        assertEquals(
                "{\"t\":1700000000000,\"player\":\"k33bz\",\"uuid\":\"u-1\","
                        + "\"up\":12,\"down\":3,\"peak_climb\":8,\"release_fall\":6,"
                        + "\"dim\":\"minecraft:overworld\"}",
                json,
                "exact schema (stable field order + compact whole numbers)");
    }

    @Test
    void fractionalNumbersAreCompactAndDotDecimal() {
        ClimbLog.Session s = new ClimbLog.Session(
                1L, "p", "u", 12.5, 0.0, 12.5, 0.0, "d");
        String json = ClimbLog.toJson(s);
        assertTrue(json.contains("\"up\":12.5"), json);
        assertTrue(json.contains("\"down\":0"), json);
        assertTrue(json.contains("\"peak_climb\":12.5"), json);
        assertTrue(json.contains("\"release_fall\":0"), json);
    }

    @Test
    void numIsLocaleIndependentAndCompact() {
        assertEquals("12", ClimbLog.num(12.0), "whole numbers drop the fractional part");
        assertEquals("12.5", ClimbLog.num(12.5), "must use a dot, never a locale comma");
        assertEquals("0", ClimbLog.num(0.0));
        assertEquals("0", ClimbLog.num(-0.0), "negative zero normalizes to 0");
        assertEquals("-3.25", ClimbLog.num(-3.25));
    }

    @Test
    void numNeverEmitsTrailingZeros() {
        // 12.50000 must render as 12.5, not 12.50000 — the requester's explicit compactness rule.
        assertFalse(ClimbLog.num(12.5).contains("0000"), ClimbLog.num(12.5));
        assertEquals("12.5", ClimbLog.num(12.500000));
    }

    @Test
    void playerNameIsJsonEscaped() {
        // An offline player could set a name with a quote/backslash; the line must stay valid JSON.
        ClimbLog.Session s = new ClimbLog.Session(
                1L, "he\"ll\\o", "u", 1.0, 0.0, 1.0, 0.0, "d");
        String json = ClimbLog.toJson(s);
        assertTrue(json.contains("\"player\":\"he\\\"ll\\\\o\""), json);
        assertFalse(json.contains("\n"), "no raw control chars inside the line");
    }

    @Test
    void dailyFileNameHasThePrefixAndZonedDate() {
        // 2024-06-15T00:00:00Z
        long millis = 1_718_409_600_000L;
        assertEquals("ropes-climbs-2024-06-15.ndjson",
                ClimbLog.dailyFileName(millis, ZoneOffset.UTC));
    }

    @Test
    void dailyFileNameRollsWithLocalMidnight() {
        long justBefore = 1_718_409_600_000L - 1;
        assertEquals("ropes-climbs-2024-06-14.ndjson",
                ClimbLog.dailyFileName(justBefore, ZoneOffset.UTC));
        assertEquals("ropes-climbs-2024-06-15.ndjson",
                ClimbLog.dailyFileName(justBefore, ZoneId.of("+02:00")));
    }
}
