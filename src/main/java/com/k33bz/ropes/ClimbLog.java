package com.k33bz.ropes;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Pure, game-runtime-free NDJSON serialization for a completed climb session, plus the daily-file
 * name derivation — the same discipline as sanctuary's {@code KillEventLog} and grieflog's
 * {@code GriefEvents}: compact, locale-independent numbers and stable field order, all static
 * functions of their inputs so they are unit-tested directly with no server (the CoE-red-provable
 * core of the log format the mc.kast.ro stats site parses).
 *
 * <p>The emitted line (one per session end) is exactly:</p>
 * <pre>{"t":&lt;epoch ms&gt;,"player":"&lt;name&gt;","uuid":"&lt;uuid&gt;","up":&lt;n&gt;,"down":&lt;n&gt;,"peak_climb":&lt;n&gt;,"release_fall":&lt;n&gt;,"dim":"&lt;dim id&gt;"}</pre>
 * <p>Field order is stable ({@code t,player,uuid,up,down,peak_climb,release_fall,dim}); numbers are
 * written with {@link #num} so whole values stay compact ({@code 12} not {@code 12.0}) and a
 * fractional value never becomes a locale decimal-comma. {@code uuid} is included beyond the
 * requester's spec for offline-mode robustness (gmc101 is offline; names hash to UUIDs) — the site
 * keys on {@code player} but the uuid is a safe extra.</p>
 */
public final class ClimbLog {
    private ClimbLog() {
    }

    /** One completed climb session, ready to serialize. All counts are block distances (double). */
    public record Session(
            long t,
            String player,
            String uuid,
            double up,
            double down,
            double peakClimb,
            double releaseFall,
            String dim
    ) {
    }

    // ------------------------------------------------------------------ NDJSON

    /**
     * Serialize a session to one compact JSON object (no trailing newline). Stable field order,
     * strings JSON-escaped, numbers via {@link #num} (compact + {@link Locale#ROOT}).
     */
    public static String toJson(Session s) {
        StringBuilder b = new StringBuilder(160);
        b.append('{');
        appendLong(b, "t", s.t(), true);
        appendStr(b, "player", s.player(), false);
        appendStr(b, "uuid", s.uuid(), false);
        appendNum(b, "up", s.up(), false);
        appendNum(b, "down", s.down(), false);
        appendNum(b, "peak_climb", s.peakClimb(), false);
        appendNum(b, "release_fall", s.releaseFall(), false);
        appendStr(b, "dim", s.dim(), false);
        b.append('}');
        return b.toString();
    }

    private static void appendStr(StringBuilder b, String key, String val, boolean first) {
        if (!first) {
            b.append(',');
        }
        b.append('"').append(key).append("\":");
        if (val == null) {
            b.append("null");
        } else {
            escape(b, val);
        }
    }

    private static void appendLong(StringBuilder b, String key, long val, boolean first) {
        if (!first) {
            b.append(',');
        }
        b.append('"').append(key).append("\":").append(val);
    }

    private static void appendNum(StringBuilder b, String key, double val, boolean first) {
        if (!first) {
            b.append(',');
        }
        b.append('"').append(key).append("\":").append(num(val));
    }

    /**
     * A whole-valued double renders without a fractional part ({@code 12} not {@code 12.0}); a
     * fractional one renders with {@link Locale#ROOT} so it is never a decimal-comma and never in
     * scientific notation for the small magnitudes involved. Negative zero normalizes to {@code 0}.
     */
    static String num(double v) {
        if (v == 0.0) {
            return "0"; // also collapses -0.0
        }
        if (v == Math.rint(v) && !Double.isInfinite(v)) {
            return Long.toString((long) v);
        }
        return String.format(Locale.ROOT, "%s", v);
    }

    /** Minimal JSON string escaping (RFC 8259): quote, backslash, and control chars &lt; 0x20. */
    static void escape(StringBuilder b, String s) {
        b.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                case '\b' -> b.append("\\b");
                case '\f' -> b.append("\\f");
                default -> {
                    if (c < 0x20) {
                        b.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        b.append('"');
    }

    // ------------------------------------------------------------ daily file

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT);

    /**
     * The NDJSON file name for a given instant + server-local zone:
     * {@code ropes-climbs-YYYY-MM-DD.ndjson}. The writer compares this to the currently-open file's
     * name each flush and rolls over on change, so a server running across local midnight starts a
     * fresh daily file with no restart.
     */
    public static String dailyFileName(long epochMillis, ZoneId zone) {
        return "ropes-climbs-"
                + DAY.format(Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate())
                + ".ndjson";
    }
}
