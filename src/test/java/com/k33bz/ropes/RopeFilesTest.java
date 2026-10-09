package com.k33bz.ropes;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The store is the only record of where every rope's invisible endpoint bat is. An unreadable
 * store used to be replaced by an empty one and saved over at boot; these pin that the original
 * survives byte for byte and that saves never leave a half-written file.
 */
class RopeFilesTest {

    @Test
    void corruptFileIsCopiedAsideByteForByte(@TempDir Path dir) throws Exception {
        Path store = dir.resolve("ropes_store.json");
        String garbage = "{\"segments\": [{\"dim\": \"minecraft:overworld\", \"fenceA\": [1, 2";
        Files.writeString(store, garbage);
        Path backup = RopeFiles.backUpCorrupt(store, 1234L);
        assertEquals("ropes_store.json.corrupt-1234", backup.getFileName().toString());
        assertEquals(garbage, Files.readString(backup));
        assertEquals(garbage, Files.readString(store)); // the original is left where it was
    }

    @Test
    void failedBackupRefusesToContinue(@TempDir Path dir) {
        // Nothing to copy: refusing beats starting empty and saving over the only copy later
        assertThrows(IllegalStateException.class, () -> RopeFiles.backUpCorrupt(dir.resolve("missing.json"), 1L));
    }

    @Test
    void atomicWriteReplacesTheFileAndLeavesNoTemp(@TempDir Path dir) throws Exception {
        Path store = dir.resolve("ropes_store.json");
        Files.writeString(store, "old");
        RopeFiles.writeAtomically(store, "{\"segments\": []}");
        assertEquals("{\"segments\": []}", Files.readString(store));
        assertFalse(Files.exists(dir.resolve("ropes_store.json.tmp")));
    }
}
