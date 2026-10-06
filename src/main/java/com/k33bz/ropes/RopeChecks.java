package com.k33bz.ropes;

import java.util.UUID;

/**
 * Small game-free rules shared by the store, the commands and the cleanup hooks, kept free of
 * Minecraft types so they unit-test without a game (0.3.1).
 */
public final class RopeChecks {
    private RopeChecks() {
    }

    /** Prefix of the per-segment tag on every knot cap: {@code ropes_knot_<segId>}. */
    public static final String KNOT_SEG_PREFIX = "ropes_knot_";

    /**
     * Whether a stored segment has everything the tick code reads: a dimension and two
     * {@code [x,y,z]} posts. A hand-edited or damaged entry missing one of these used to throw a
     * NullPointerException inside the server tick (the verify sweep, the climb check), which
     * crashes the server, and keeps crashing it on every restart because the entry is still there.
     */
    public static boolean wellFormed(String dim, int[] fenceA, int[] fenceB) {
        return dim != null && !dim.isBlank() && isPos(fenceA) && isPos(fenceB);
    }

    private static boolean isPos(int[] p) {
        return p != null && p.length == 3;
    }

    /** The UUID in {@code s}, or null when it is missing or malformed (never throws). */
    public static UUID parseUuid(String s) {
        if (s == null) {
            return null;
        }
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Whether the centre of block {@code (bx,by,bz)} is within {@code reach} blocks of an eye at
     * {@code (ex,ey,ez)}. {@code /rope tie} from a survival player needs both posts in reach, so the
     * command can't string ropes across someone else's base from the other side of the world.
     */
    public static boolean withinReach(double ex, double ey, double ez, int bx, int by, int bz, double reach) {
        double dx = bx + 0.5 - ex;
        double dy = by + 0.5 - ey;
        double dz = bz + 0.5 - ez;
        return dx * dx + dy * dy + dz * dz <= reach * reach;
    }

    /** The segment id in a knot cap's tags ({@code ropes_knot_<segId>}), or null when it has none. */
    public static String segIdOfKnotTags(Iterable<String> tags) {
        for (String t : tags) {
            if (t != null && t.startsWith(KNOT_SEG_PREFIX) && t.length() > KNOT_SEG_PREFIX.length()) {
                return t.substring(KNOT_SEG_PREFIX.length());
            }
        }
        return null;
    }

    /**
     * The knot-head texture value if it is plain base64, else "". The value is pasted into a
     * {@code summon} command, so a quote or brace in it could rewrite the command (it runs with
     * console permission). Texture values are always base64, so nothing legitimate is lost.
     */
    public static String cleanTexture(String tex) {
        if (tex == null || tex.isBlank()) {
            return "";
        }
        String t = tex.strip();
        return t.matches("[A-Za-z0-9+/=]+") ? t : "";
    }
}
