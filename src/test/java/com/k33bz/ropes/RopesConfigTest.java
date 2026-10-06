package com.k33bz.ropes;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The tick trusts every config field, so whatever the file says, sanitize() must leave values the
 * tick can run on (before 0.3.1 only the span, the log dir and two tick counts were checked).
 */
class RopesConfigTest {

    private static RopesConfig parse(String json) {
        RopesConfig cfg = new Gson().fromJson(json, RopesConfig.class);
        cfg.sanitize();
        return cfg;
    }

    @Test
    void defaultsSurviveSanitizeUnchanged() {
        RopesConfig cfg = new RopesConfig();
        cfg.sanitize();
        assertEquals(11, cfg.maxSpanBlocks);
        assertEquals(2000, cfg.maxSegments);
        assertEquals(200, cfg.verifyIntervalTicks);
        assertEquals(6.0, cfg.tieReachBlocks);
        assertEquals(0.35, cfg.knotScale);
        assertEquals(75.0, cfg.climbMinAngleDeg);
        assertEquals(0.6, cfg.climbReach);
        assertEquals(1.8, cfg.climbMaxRate);
        assertEquals("", cfg.knotHeadTexture);
        assertEquals("config/ropes_logs", cfg.climbLogDir);
    }

    @Test
    void outOfRangeNumbersAreClamped() {
        RopesConfig cfg = parse("{\"maxSpanBlocks\": 40, \"maxSegments\": -1, \"verifyIntervalTicks\": -5,"
                + " \"tieReachBlocks\": 500, \"knotScale\": 0, \"climbMinAngleDeg\": 200, \"climbReach\": 50,"
                + " \"climbLookDeg\": -10, \"climbMaxRate\": 99, \"climbFloorRate\": -1,"
                + " \"climbSessionGraceTicks\": 0, \"climbLogFlushIntervalTicks\": -3}");
        assertEquals(11, cfg.maxSpanBlocks);
        assertEquals(0, cfg.maxSegments);
        assertEquals(0, cfg.verifyIntervalTicks);
        assertEquals(RopesConfig.TIE_REACH_MAX, cfg.tieReachBlocks);
        assertEquals(RopesConfig.KNOT_SCALE_MIN, cfg.knotScale);
        assertEquals(90.0, cfg.climbMinAngleDeg);
        assertEquals(RopesConfig.CLIMB_REACH_MAX, cfg.climbReach);
        assertEquals(0.0, cfg.climbLookDeg);
        assertEquals(RopesConfig.RATE_MAX, cfg.climbMaxRate);
        assertEquals(0.0, cfg.climbFloorRate);
        assertEquals(1, cfg.climbSessionGraceTicks);
        assertEquals(1, cfg.climbLogFlushIntervalTicks);
    }

    @Test
    void climbRateNeverReachesLadderSpeed() {
        RopesConfig cfg = parse("{\"climbMaxRate\": 10, \"climbVerticalRate\": 10}");
        assertTrue(cfg.climbMaxRate < 2.35);
        assertTrue(cfg.climbVerticalRate < 2.35);
    }

    @Test
    void nanFallsBackToTheDefault() {
        RopesConfig cfg = new RopesConfig();
        cfg.knotScale = Double.NaN;
        cfg.climbReach = Double.NaN;
        cfg.climbMaxRate = Double.NaN;
        cfg.tieReachBlocks = Double.NaN;
        cfg.sanitize();
        assertEquals(0.35, cfg.knotScale);
        assertEquals(0.6, cfg.climbReach);
        assertEquals(1.8, cfg.climbMaxRate);
        assertEquals(6.0, cfg.tieReachBlocks);
        assertFalse(Double.isNaN(new Gson().toJsonTree(cfg).getAsJsonObject().get("knotScale").getAsDouble()));
    }

    @Test
    void blankLogDirAndBadTextureAreRepaired() {
        RopesConfig cfg = parse("{\"climbLogDir\": \"  \", \"knotHeadTexture\": \"x\\\"}]}\"}");
        assertEquals("config/ropes_logs", cfg.climbLogDir);
        assertEquals("", cfg.knotHeadTexture);
    }

    @Test
    void missingKeysKeepTheirDefaults() {
        RopesConfig cfg = parse("{\"maxSpanBlocks\": 8}");
        assertEquals(8, cfg.maxSpanBlocks);
        assertEquals(2000, cfg.maxSegments);
        assertTrue(cfg.climbEnabled);
    }
}
