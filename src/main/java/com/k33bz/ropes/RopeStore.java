package com.k33bz.ropes;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The rope-segment store — {@code config/ropes_store.json} (gson, pretty), the same
 * despawn-proof, JSON-not-in-world pattern as postbox's Mail store. Everything the mod needs to
 * re-verify and clean up a rope lives here; the leash attachment itself lives on the endpoint
 * entity (which vanilla persists), and the fence knot is re-anchored by vanilla on reload.
 *
 * <p><b>CoE lesson applied:</b> every mutation ({@link #add}, {@link #remove}) writes the file
 * immediately. We never defer a save to a later caller who might forget it — a crash between a
 * mutation and a batched save would silently lose a rope.</p>
 *
 * <p>Schema (v1):
 * <pre>
 * { "segments": [
 *     { "dim": "minecraft:overworld",
 *       "fenceA": [x, y, z],          // the knot-holding fence (leash HOLDER end)
 *       "fenceB": [x, y, z],          // the endpoint-mob fence (leash MOVING end)
 *       "endpointUuid": "…",          // the invisible bat leashed to fenceA's knot
 *       "owner": "…" }                // uuid of the player who strung it (audit; may be null)
 * ] }
 * </pre></p>
 */
public final class RopeStore {
    private RopeStore() {
    }

    /** One rope segment: fence A holds the knot, fence B holds the endpoint mob leashed to it. */
    public static final class Segment {
        public String dim;
        public int[] fenceA;      // [x,y,z] — the leash-holder fence
        public int[] fenceB;      // [x,y,z] — the endpoint-mob fence
        public String endpointUuid;
        public String owner;      // uuid string; nullable

        public Segment() {
        }

        public Segment(String dim, int[] fenceA, int[] fenceB, String endpointUuid, String owner) {
            this.dim = dim;
            this.fenceA = fenceA;
            this.fenceB = fenceB;
            this.endpointUuid = endpointUuid;
            this.owner = owner;
        }

        public boolean touches(String dim, int x, int y, int z) {
            return java.util.Objects.equals(this.dim, dim)
                    && ((fenceA[0] == x && fenceA[1] == y && fenceA[2] == z)
                    || (fenceB[0] == x && fenceB[1] == y && fenceB[2] == z));
        }
    }

    public static final class Store {
        public List<Segment> segments = new ArrayList<>();
    }

    private static Store store;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("ropes_store.json");
    }

    public static Store store() {
        if (store == null) {
            try {
                if (Files.exists(path())) {
                    store = GSON.fromJson(Files.readString(path()), new TypeToken<Store>() { }.getType());
                }
            } catch (Exception e) {
                Ropes.LOGGER.warn("[ropes] could not read rope store", e);
            }
            if (store == null) {
                store = new Store();
            }
            if (store.segments == null) {
                store.segments = new ArrayList<>();
            }
        }
        return store;
    }

    public static void save() {
        try {
            Files.writeString(path(), GSON.toJson(store()));
        } catch (IOException e) {
            Ropes.LOGGER.warn("[ropes] could not save rope store", e);
        }
    }

    public static List<Segment> segments() {
        return store().segments;
    }

    /** Add a segment and persist immediately (CoE: never defer the save). */
    public static void add(Segment seg) {
        store().segments.add(seg);
        save();
    }

    /** Remove a segment and persist immediately. */
    public static void remove(Segment seg) {
        store().segments.remove(seg);
        save();
    }

    /** The segment with the given endpoint UUID, or null. */
    public static Segment byEndpoint(String uuid) {
        for (Segment s : store().segments) {
            if (uuid.equals(s.endpointUuid)) {
                return s;
            }
        }
        return null;
    }

    /** Every segment that has an endpoint at the given fence block (A or B). */
    public static List<Segment> touching(String dim, int x, int y, int z) {
        List<Segment> out = new ArrayList<>();
        for (Segment s : store().segments) {
            if (s.touches(dim, x, y, z)) {
                out.add(s);
            }
        }
        return out;
    }

    /** Whether any segment (other than {@code except}) still anchors a knot at this fence. */
    public static boolean anotherRopeUses(String dim, int x, int y, int z, Segment except) {
        for (Segment s : store().segments) {
            if (s != except && java.util.Objects.equals(s.dim, dim)
                    && s.fenceA[0] == x && s.fenceA[1] == y && s.fenceA[2] == z) {
                return true;
            }
        }
        return false;
    }
}
