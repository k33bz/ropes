package com.k33bz.ropes;

import java.util.ArrayList;
import java.util.List;

/**
 * A cheap, queryable view of the live rope segments — geometry exposed for systems that read it
 * <b>every tick</b> without entity-scanning (e.g. a future climb detector that asks "is this
 * player's hitbox near a near-vertical segment?"). This is a thin facade over {@link RopeStore};
 * the store remains the source of truth and the persistence layer, and every mutation there is
 * reflected here immediately because we read the same live list.
 *
 * <p>Each segment already carries its two endpoints (fenceA / fenceB); this class adds the derived
 * geometry (slope, verticality, distance-to-point) as pure functions so no per-tick allocation or
 * world lookup is needed.</p>
 */
public final class RopeRegistry {
    private RopeRegistry() {
    }

    /** A slope descriptor for a segment: horizontal run, vertical rise, and their ratio. */
    public record Geometry(double dx, double dy, double dz, double horizontal, double length) {
        /** Vertical rise over horizontal run; {@link Double#POSITIVE_INFINITY} for a plumb line. */
        public double slope() {
            return horizontal < 1.0e-6 ? Double.POSITIVE_INFINITY : Math.abs(dy) / horizontal;
        }

        /** Angle above horizontal, radians (0 = flat, PI/2 = vertical). */
        public double pitch() {
            return Math.atan2(Math.abs(dy), horizontal);
        }

        /**
         * Whether this segment is "near-vertical" — steeper than {@code minPitchRadians} above
         * horizontal. A climb system uses this to decide a segment is climbable.
         */
        public boolean isNearVertical(double minPitchRadians) {
            return pitch() >= minPitchRadians;
        }
    }

    /** Every segment currently stored (live list — do not mutate; use {@link RopeStore}). */
    public static List<RopeStore.Segment> all() {
        return RopeStore.segments();
    }

    /** Every segment in the given dimension. */
    public static List<RopeStore.Segment> inDimension(String dim) {
        List<RopeStore.Segment> out = new ArrayList<>();
        for (RopeStore.Segment s : RopeStore.segments()) {
            if (s.dim.equals(dim)) {
                out.add(s);
            }
        }
        return out;
    }

    /** Precomputed geometry of a segment (fenceA &rarr; fenceB). Pure; no allocation beyond the record. */
    public static Geometry geometryOf(RopeStore.Segment s) {
        double dx = s.fenceB[0] - s.fenceA[0];
        double dy = s.fenceB[1] - s.fenceA[1];
        double dz = s.fenceB[2] - s.fenceA[2];
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        return new Geometry(dx, dy, dz, horizontal, length);
    }

    /**
     * The shortest distance from a point to a segment's line (clamped to the segment's extent).
     * A climb detector calls this with the player's hitbox centre to cheaply test proximity — no
     * entity scan, just arithmetic over the two stored endpoints.
     */
    public static double distanceToSegment(RopeStore.Segment s, double px, double py, double pz) {
        double ax = s.fenceA[0] + 0.5, ay = s.fenceA[1] + 0.5, az = s.fenceA[2] + 0.5;
        double bx = s.fenceB[0] + 0.5, by = s.fenceB[1] + 0.5, bz = s.fenceB[2] + 0.5;
        double abx = bx - ax, aby = by - ay, abz = bz - az;
        double abLenSq = abx * abx + aby * aby + abz * abz;
        double t = abLenSq < 1.0e-9 ? 0.0
                : ((px - ax) * abx + (py - ay) * aby + (pz - az) * abz) / abLenSq;
        t = Math.max(0.0, Math.min(1.0, t));
        double cx = ax + t * abx, cy = ay + t * aby, cz = az + t * abz;
        double ddx = px - cx, ddy = py - cy, ddz = pz - cz;
        return Math.sqrt(ddx * ddx + ddy * ddy + ddz * ddz);
    }

    /**
     * The nearest near-vertical segment within {@code radius} of a point, or {@code null}. This is
     * the query a climb detector runs each tick: "is the player next to a rope they could climb?"
     * — answered from the registry's stored endpoints alone, no world/entity lookup.
     *
     * @param dim            the player's dimension
     * @param minPitchRadians how steep counts as climbable (e.g. Math.toRadians(60))
     */
    public static RopeStore.Segment nearestClimbable(String dim, double px, double py, double pz,
                                                     double radius, double minPitchRadians) {
        RopeStore.Segment best = null;
        double bestDist = radius;
        for (RopeStore.Segment s : RopeStore.segments()) {
            if (!s.dim.equals(dim) || !geometryOf(s).isNearVertical(minPitchRadians)) {
                continue;
            }
            double d = distanceToSegment(s, px, py, pz);
            if (d <= bestDist) {
                bestDist = d;
                best = s;
            }
        }
        return best;
    }
}
