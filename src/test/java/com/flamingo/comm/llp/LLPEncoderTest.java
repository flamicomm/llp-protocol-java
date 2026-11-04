package com.flamingo.comm.llp;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Encoder behaviour, including the examples printed in the specification
 * ({@code docs/SPEC-v4.md} §3) and the three places where stuffing can happen.
 */
class LLPEncoderTest {

    private static final HexFormat HEX = HexFormat.of();

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    @Test
    void emptyPayloadMatchesTheSpecificationExample() {
        assertEquals("aa55000023b3", HEX.formatHex(LLPEncoder.encode(0, new byte[0])));
    }

    @Test
    void textPayloadMatchesTheSpecificationExample() {
        assertEquals("aa5503006f6b0a08db", HEX.formatHex(LLPEncoder.encode(0, ascii("ok\n"))));
    }

    @Test
    void commandOnChannelOneMatchesTheSpecificationExample() {
        assertEquals("aa5507014731205831300ae427",
                HEX.formatHex(LLPEncoder.encode(1, ascii("G1 X10\n"))));
    }

    @Test
    void payloadContainingTheMagicIsStuffed() {
        assertEquals("aa550200aa0055d24a",
                HEX.formatHex(LLPEncoder.encode(0, new byte[]{(byte) 0xAA, 0x55})));
    }

    @Test
    void allAaPayloadDoublesOnTheWire() {
        byte[] frame = LLPEncoder.encode(0, new byte[]{(byte) 0xAA, (byte) 0xAA});
        assertEquals("aa550200aa00aa002254", HEX.formatHex(frame));
    }

    @Test
    void lengthFieldIsStuffedWhenItEqualsAa() {
        byte[] frame = LLPEncoder.encode(0, new byte[170]);
        // magic, then LEN = 0xAA stuffed as AA 00, then CHANNEL = 0x00
        assertEquals("aa55aa0000", HEX.formatHex(frame).substring(0, 10));
    }

    @Test
    void channelFieldIsStuffedWhenItEqualsAa() {
        byte[] frame = LLPEncoder.encode(0xAA, ascii("hi"));
        assertEquals("aa5502aa00", HEX.formatHex(frame).substring(0, 10));
    }

    @Test
    void crcFieldIsStuffedWhenItContainsAa() {
        // payload 0x64 has CRC 0xAFAA, so CRC_L = 0xAA must be escaped
        assertEquals("aa55010064aa00af", HEX.formatHex(LLPEncoder.encode(0, new byte[]{0x64})));
    }

    @Test
    void frameSizeIsTheWorstCase() {
        assertEquals(10, LLPEncoder.frameSize(0));
        assertEquals(16, LLPEncoder.frameSize(3));
        assertEquals(520, LLPEncoder.frameSize(255));
    }

    @Test
    void frameSizeRejectsInvalidLengths() {
        assertThrows(LLPException.class, () -> LLPEncoder.frameSize(-1));
        assertThrows(LLPException.class, () -> LLPEncoder.frameSize(256));
    }

    @Test
    void encodeSliceUsesOnlyTheRequestedRange() {
        byte[] source = ascii("xxok\nyy");
        byte[] frame = LLPEncoder.encode(0, source, 2, 3);
        assertEquals("aa5503006f6b0a08db", HEX.formatHex(frame));
    }

    @Test
    void encodeSliceRejectsOutOfBoundsRanges() {
        byte[] source = new byte[4];
        assertThrows(LLPException.class, () -> LLPEncoder.encode(0, source, 3, 2));
        assertThrows(LLPException.class, () -> LLPEncoder.encode(0, source, -1, 1));
        assertThrows(LLPException.class, () -> LLPEncoder.encode(0, null));
    }

    @Test
    void rejectsPayloadOver255Bytes() {
        assertThrows(LLPException.class, () -> LLPEncoder.encode(0, new byte[256]));
    }

    @Test
    void rejectsChannelOutOfRange() {
        assertThrows(LLPException.class, () -> LLPEncoder.encode(-1, new byte[0]));
        assertThrows(LLPException.class, () -> LLPEncoder.encode(256, new byte[0]));
    }

    @Test
    void streamingMatchesTheConveniencePath() {
        byte[] payload = new byte[]{0x00, (byte) 0xAA, 0x55, 0x7F};

        StringBuilder hex = new StringBuilder();
        LLPEncoder encoder = new LLPEncoder(3, payload.length, b -> hex.append(HEX.formatHex(new byte[]{b})));
        for (byte b : payload) {
            encoder.payloadByte(b);
        }
        encoder.end();

        assertEquals(HEX.formatHex(LLPEncoder.encode(3, payload)), hex.toString());
    }

    @Test
    void streamingRejectsTooManyPayloadBytes() {
        LLPEncoder encoder = new LLPEncoder(0, 1, b -> { });
        encoder.payloadByte((byte) 0x01);
        assertThrows(LLPException.class, () -> encoder.payloadByte((byte) 0x02));
    }

    @Test
    void streamingRejectsMissingPayloadBytes() {
        LLPEncoder encoder = new LLPEncoder(0, 2, b -> { });
        encoder.payloadByte((byte) 0x01);
        assertThrows(LLPException.class, encoder::end);
    }

    @Test
    void streamingValidatesItsArguments() {
        assertThrows(LLPException.class, () -> new LLPEncoder(-1, 0, b -> { }));
        assertThrows(LLPException.class, () -> new LLPEncoder(0, 256, b -> { }));
        assertThrows(LLPException.class, () -> new LLPEncoder(0, 0, null));
    }

    @Test
    void encodedFramesAreExactLengthArrays() {
        byte[] frame = LLPEncoder.encode(0, ascii("abc"));
        assertEquals(6 + 3, frame.length);
        assertArrayEquals(HEX.parseHex("616263"),
                java.util.Arrays.copyOfRange(frame, 4, 7));
    }

    @Test
    void encodingIsDeterministic() {
        assertArrayEquals(LLPEncoder.encode(7, ascii("same")), LLPEncoder.encode(7, ascii("same")));
    }
}
