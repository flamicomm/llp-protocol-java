package com.flamingo.comm.llp.spec;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the vendored corpus.
 *
 * <p>{@code src/test/resources/llp-vectors/} is a verbatim copy of
 * {@code llp-spec/vectors/}. The manifest pins each file, so a hand edit here
 * (or a partial sync) fails the build instead of quietly changing the contract.
 * Refresh the copy with {@code tools/sync-vectors.sh}.</p>
 */
class VectorManifestTest {

    private static final String RESOURCE_DIR = "llp-vectors";
    private static final int EXPECTED_FILES = 8;

    @Test
    void vendoredCorpusMatchesItsManifest() throws Exception {
        Path dir = VectorLoader.directory(RESOURCE_DIR);
        List<String> lines = Files.readAllLines(dir.resolve("MANIFEST.sha256"));

        int checked = 0;
        for (String line : lines) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String[] parts = line.trim().split("\\s+");
            assertEquals(2, parts.length, "malformed manifest line: " + line);

            Path file = dir.resolve(parts[1]);
            assertTrue(Files.isRegularFile(file), "missing vector file: " + parts[1]);
            assertEquals(parts[0], sha256(file), "checksum mismatch for " + parts[1]);
            checked++;
        }

        assertEquals(EXPECTED_FILES, checked, "unexpected number of vector files");
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(Files.readAllBytes(file)));
    }
}
