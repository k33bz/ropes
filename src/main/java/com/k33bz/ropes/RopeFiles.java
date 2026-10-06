package com.k33bz.ropes;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * File handling for the rope store and config, kept free of Minecraft types so it unit-tests
 * without a game (the same helper as postbox's MailFiles).
 *
 * <ul>
 *   <li>A file that can't be parsed is never thrown away. It is copied aside to
 *       {@code <name>.corrupt-<millis>} before ropes starts over, so an admin can repair and
 *       restore it. Before 0.3.1 the empty replacement was saved straight over the store at boot,
 *       losing every rope and leaving its invisible endpoint bats behind with nothing to track
 *       them.</li>
 *   <li>Saves are atomic: written to a temp file, then moved over the original, so a crash
 *       mid-save cannot leave a half-written file behind.</li>
 * </ul>
 */
public final class RopeFiles {
    private RopeFiles() {
    }

    /**
     * Copy an unreadable file aside and return the copy's path. Throws if the copy fails: refusing
     * to start is better than carrying on and later saving an empty store over the only copy.
     */
    public static Path backUpCorrupt(Path file, long nowMs) {
        Path backup = file.resolveSibling(file.getFileName() + ".corrupt-" + nowMs);
        try {
            Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING);
            return backup;
        } catch (IOException e) {
            throw new IllegalStateException("ropes: " + file + " could not be read and could not be backed up to "
                    + backup + "; refusing to continue rather than overwrite it", e);
        }
    }

    /** Write {@code content} to {@code file} via a temp file + atomic move (plain replace as fallback). */
    public static void writeAtomically(Path file, String content) throws IOException {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, content);
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
