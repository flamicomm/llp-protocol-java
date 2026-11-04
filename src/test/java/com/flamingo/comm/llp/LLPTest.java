package com.flamingo.comm.llp;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The facade: defaults, builder validation and the static encoder helpers.
 */
class LLPTest {

    private static final HexFormat HEX = HexFormat.of();

    @Test
    void parserDefaultsAreTheSpecificationDefaults() {
        LLPParser parser = LLP.parser().build();
        assertEquals(LLP.MAX_CAPACITY, parser.capacity());
        assertEquals(LLP.DEFAULT_TIMEOUT_MS, parser.timeoutMs());
        assertEquals(255, LLP.MAX_CAPACITY);
        assertEquals(2000, LLP.DEFAULT_TIMEOUT_MS);
    }

    @Test
    void parserBuilderAppliesTheConfiguration() {
        LLPParser parser = LLP.parser().capacity(64).timeoutMs(500).build();
        assertEquals(64, parser.capacity());
        assertEquals(500, parser.timeoutMs());
    }

    @Test
    void incrementalParserBuilderAppliesTheConfiguration() {
        LLPIncrementalParser receiver = LLP.incrementalParser().capacity(16).timeoutMs(250).build();
        assertEquals(16, receiver.parser().capacity());
        assertEquals(250, receiver.parser().timeoutMs());
    }

    @Test
    void buildRejectsInvalidConfiguration() {
        assertThrows(LLPException.class, () -> LLP.parser().capacity(256).build());
        assertThrows(LLPException.class, () -> LLP.parser().timeoutMs(0).build());
        assertThrows(LLPException.class, () -> LLP.incrementalParser().timeoutMs(40000).build());
    }

    @Test
    void encodeMatchesTheEncoder() {
        byte[] payload = "hello".getBytes(StandardCharsets.US_ASCII);
        assertArrayEquals(LLPEncoder.encode(3, payload), LLP.encode(3, payload));
        assertEquals("aa5503006f6b0a08db",
                HEX.formatHex(LLP.encode(0, "ok\n".getBytes(StandardCharsets.US_ASCII))));
    }

    @Test
    void frameSizeIsTheWorstCase() {
        assertEquals(10, LLP.frameSize(0));
        assertEquals(520, LLP.frameSize(255));
        assertThrows(LLPException.class, () -> LLP.frameSize(256));
    }
}
