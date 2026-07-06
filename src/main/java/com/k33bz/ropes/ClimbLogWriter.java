package com.k33bz.ropes;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.ZoneId;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The append-only NDJSON sink for climb sessions — the exact buffered-append daily-file discipline
 * grieflog's {@code GriefLogWriter} established. The <b>game thread never touches the
 * filesystem</b>: {@link #enqueue} does a single lock-free {@link ConcurrentLinkedQueue#offer} and
 * returns. A drain — driven off the end-of-server-tick every {@code flushIntervalTicks} (see
 * {@link Ropes}) — pulls the queue, appends each line through a {@link BufferedWriter}, and flushes
 * once per drain.
 *
 * <p><b>Daily rollover:</b> before writing a batch the drain derives the current file name from the
 * server-local date ({@link ClimbLog#dailyFileName}); when it differs from the open file it closes
 * the old writer and opens the new one, so a server crossing local midnight rolls to a fresh
 * {@code ropes-climbs-YYYY-MM-DD.ndjson} with no restart.</p>
 *
 * <p><b>Durability window:</b> sessions sit in the queue (and the writer's buffer) between flushes;
 * a hard crash can lose at most one flush window. A clean shutdown loses nothing: {@link #shutdown}
 * drains, flushes, and closes — wired to {@code SERVER_STOPPING}. Climb volume is far below
 * grieflog's honeypot floods (one line per completed session), so no back-pressure cap is needed,
 * but a generous soft cap is kept for parity/safety.</p>
 *
 * <p>Threading contract: {@link #enqueue} is safe from any thread (game thread). {@link #drain} and
 * {@link #shutdown} run only on the single tick-owner thread, so the {@link BufferedWriter} is never
 * touched concurrently.</p>
 */
public final class ClimbLogWriter {

    private final Path dir;
    private final ZoneId zone;
    private final ConcurrentLinkedQueue<String> queue = new ConcurrentLinkedQueue<>();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong written = new AtomicLong();

    /** Soft back-pressure cap (huge relative to real climb volume) — a safety valve, not a limit. */
    private static final int MAX_QUEUE = 200_000;

    private BufferedWriter out;
    private String openFileName; // name of the currently-open daily file, or null

    public ClimbLogWriter(Path dir, ZoneId zone) {
        this.dir = dir;
        this.zone = zone;
    }

    /** Enqueue a pre-serialized NDJSON line (no newline). Lock-free and non-blocking (game thread). */
    public void enqueue(String line) {
        if (queue.size() >= MAX_QUEUE) {
            dropped.incrementAndGet();
            return;
        }
        queue.offer(line);
    }

    /** Serialize on the game thread (cheap, allocation-only) and enqueue. */
    public void enqueue(ClimbLog.Session s) {
        enqueue(ClimbLog.toJson(s));
    }

    /**
     * Drain everything queued so far to disk and flush. Rolls the daily file first if the date
     * changed. Runs on the tick-owner thread only. Never throws to the caller — an I/O failure is
     * logged and the batch dropped so a full disk can't crash the server tick loop.
     */
    public void drain() {
        if (queue.isEmpty()) {
            return;
        }
        try {
            rollIfNeeded(System.currentTimeMillis());
            String line;
            int n = 0;
            while ((line = queue.poll()) != null) {
                out.write(line);
                out.write('\n');
                n++;
            }
            out.flush();
            written.addAndGet(n);
        } catch (IOException ex) {
            Ropes.LOGGER.warn("[ropes] climb-log flush failed; dropping this batch", ex);
            closeQuietly();
        }
    }

    /** Open (or re-open) the daily file if the server-local date has rolled. */
    private void rollIfNeeded(long now) throws IOException {
        String want = ClimbLog.dailyFileName(now, zone);
        if (want.equals(openFileName) && out != null) {
            return;
        }
        closeQuietly();
        Files.createDirectories(dir);
        Path file = dir.resolve(want);
        out = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        openFileName = want;
        Ropes.LOGGER.info("[ropes] climb log -> {}", file.toAbsolutePath());
    }

    /** Final drain + flush + close on server stop. After this the writer is unusable. */
    public void shutdown() {
        drain();
        closeQuietly();
        long d = dropped.get();
        if (d > 0) {
            Ropes.LOGGER.warn("[ropes] dropped {} climb session(s) under back-pressure (queue > {})",
                    d, MAX_QUEUE);
        }
        Ropes.LOGGER.info("[ropes] wrote {} climb session line(s)", written.get());
    }

    private void closeQuietly() {
        if (out != null) {
            try {
                out.flush();
                out.close();
            } catch (IOException ignored) {
                // best-effort on shutdown / after an I/O error
            }
            out = null;
            openFileName = null;
        }
    }

    /** For tests / diagnostics: how many lines have been written to disk so far. */
    public long writtenCount() {
        return written.get();
    }
}
