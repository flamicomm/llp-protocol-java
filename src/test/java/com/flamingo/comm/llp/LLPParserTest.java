package com.flamingo.comm.llp;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ReadOnlyBufferException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behaviour of the byte-at-a-time parser: the normative invariants of
 * {@code docs/SPEC-v4.md} §6, the four errors of §7 and the timeout rules of §8.
 */
class LLPParserTest {

    private static final HexFormat HEX = HexFormat.of();

    private static LLPParser parser() {
        return new LLPParser(LLPParser.MAX_CAPACITY, LLPParser.DEFAULT_TIMEOUT_MS);
    }

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] bytes(ByteBuffer buffer) {
        byte[] out = new byte[buffer.remaining()];
        buffer.duplicate().get(out);
        return out;
    }

    /** Collects what the parser reports while bytes are fed. */
    private static final class Collector {
        final List<Integer> channels = new ArrayList<>();
        final List<byte[]> payloads = new ArrayList<>();
        final List<LLPError> errors = new ArrayList<>();

        FeedResult feed(LLPParser parser, byte b, int now) {
            FeedResult result = parser.feed(b, now);
            if (result instanceof FeedResult.Frame) {
                channels.add(parser.channel());
                payloads.add(bytes(parser.payload()));
            } else if (result instanceof FeedResult.Error error) {
                errors.add(error.value());
            }
            return result;
        }

        FeedResult feed(LLPParser parser, byte[] data, int now) {
            FeedResult last = FeedResult.NONE;
            for (byte b : data) {
                last = feed(parser, b, now);
            }
            return last;
        }
    }

    // ------------------------------------------------------------------
    // Valid frames
    // ------------------------------------------------------------------

    @Test
    void deliversAnEmptyFrame() {
        Collector c = new Collector();
        c.feed(parser(), LLPEncoder.encode(0, new byte[0]), 0);

        assertEquals(1, c.payloads.size());
        assertArrayEquals(new byte[0], c.payloads.get(0));
        assertEquals(0, c.channels.get(0));
        assertTrue(c.errors.isEmpty());
    }

    @Test
    void deliversChannelAndPayload() {
        Collector c = new Collector();
        c.feed(parser(), LLPEncoder.encode(1, ascii("G1 X10\n")), 0);

        assertEquals(1, c.payloads.size());
        assertEquals(1, c.channels.get(0));
        assertArrayEquals(ascii("G1 X10\n"), c.payloads.get(0));
    }

    @Test
    void deliversPayloadsContainingTheMagic() {
        Collector c = new Collector();
        c.feed(parser(), LLPEncoder.encode(0, new byte[]{(byte) 0xAA, 0x55, 0x00}), 0);

        assertEquals(1, c.payloads.size());
        assertArrayEquals(new byte[]{(byte) 0xAA, 0x55, 0x00}, c.payloads.get(0));
        assertTrue(c.errors.isEmpty());
    }

    @Test
    void acceptsAaAa55AsAFrameStart() {
        byte[] frame = LLPEncoder.encode(0, new byte[0]);
        byte[] prefixed = new byte[frame.length + 1];
        prefixed[0] = (byte) 0xAA;
        System.arraycopy(frame, 0, prefixed, 1, frame.length);

        Collector c = new Collector();
        c.feed(parser(), prefixed, 0);

        assertEquals(1, c.payloads.size());
        assertTrue(c.errors.isEmpty());
    }

    @Test
    void ignoresGarbageWhileWaitingForTheMagic() {
        byte[] frame = LLPEncoder.encode(0, ascii("ok\n"));
        byte[] noisy = new byte[frame.length + 4];
        noisy[0] = 0x00;
        noisy[1] = (byte) 0xFF;
        noisy[2] = (byte) 0x13;
        noisy[3] = (byte) 0xAA;
        System.arraycopy(frame, 0, noisy, 4, frame.length);

        Collector c = new Collector();
        c.feed(parser(), noisy, 0);

        assertEquals(1, c.payloads.size());
        assertTrue(c.errors.isEmpty(), "garbage must never be reported as an error");
    }

    @Test
    void acceptsLengthEqualToCapacity() {
        Collector c = new Collector();
        LLPParser parser = new LLPParser(4, 2000);
        c.feed(parser, LLPEncoder.encode(0, ascii("abcd")), 0);

        assertEquals(1, c.payloads.size());
        assertArrayEquals(ascii("abcd"), c.payloads.get(0));
    }

    // ------------------------------------------------------------------
    // Errors
    // ------------------------------------------------------------------

    @Test
    void reportsCrcAndDiscardsTheFrame() {
        byte[] frame = LLPEncoder.encode(0, ascii("ok\n"));
        frame[frame.length - 1] ^= 0xFF;

        Collector c = new Collector();
        c.feed(parser(), frame, 0);

        assertTrue(c.payloads.isEmpty(), "a corrupt frame must not be delivered");
        assertEquals(List.of(LLPError.CRC), c.errors);
    }

    @Test
    void reportsSyncOnANewMagicInsideAFrame() {
        byte[] partial = {(byte) 0xAA, 0x55, 0x05, 0x00, 0x68};
        byte[] good = LLPEncoder.encode(1, ascii("after"));

        byte[] stream = new byte[partial.length + good.length];
        System.arraycopy(partial, 0, stream, 0, partial.length);
        System.arraycopy(good, 0, stream, partial.length, good.length);

        Collector c = new Collector();
        c.feed(parser(), stream, 0);

        assertEquals(List.of(LLPError.SYNC), c.errors);
        assertEquals(1, c.payloads.size());
        assertEquals(1, c.channels.get(0));
        assertArrayEquals(ascii("after"), c.payloads.get(0));
    }

    @Test
    void reportsSyncOnABadEscapeSequence() {
        Collector c = new Collector();
        c.feed(parser(), HEX.parseHex("aa550300aa01"), 0);

        assertEquals(List.of(LLPError.SYNC), c.errors);
        assertTrue(c.payloads.isEmpty());
    }

    @Test
    void reportsLengthOverCapacityWithoutStoringPayload() {
        LLPParser parser = new LLPParser(4, 2000);
        Collector c = new Collector();
        c.feed(parser, LLPEncoder.encode(0, new byte[10]), 0);

        assertEquals(List.of(LLPError.LEN), c.errors);
        assertTrue(c.payloads.isEmpty());
        assertEquals(0, parser.length(), "no payload byte may be stored before LEN is validated");
        assertEquals(1, parser.statistics().framesError());
    }

    @Test
    void reportsLengthForAZeroCapacityParser() {
        Collector c = new Collector();
        c.feed(new LLPParser(0, 2000), LLPEncoder.encode(0, new byte[]{0x01}), 0);

        assertEquals(List.of(LLPError.LEN), c.errors);
    }

    @Test
    void recoversAfterAnError() {
        LLPParser parser = parser();
        Collector c = new Collector();

        byte[] corrupt = LLPEncoder.encode(0, ascii("bad"));
        corrupt[corrupt.length - 1] ^= 0xFF;
        c.feed(parser, corrupt, 0);
        c.feed(parser, LLPEncoder.encode(2, ascii("good")), 1);

        assertEquals(List.of(LLPError.CRC), c.errors);
        assertEquals(1, c.payloads.size());
        assertEquals(2, c.channels.get(0));
        assertArrayEquals(ascii("good"), c.payloads.get(0));
    }

    // ------------------------------------------------------------------
    // Timeout
    // ------------------------------------------------------------------

    @Test
    void reportsATimeoutInTheMiddleOfAFrame() {
        LLPParser parser = parser();
        Collector c = new Collector();
        c.feed(parser, new byte[]{(byte) 0xAA, 0x55, 0x03, 0x00}, 0);

        FeedResult result = c.feed(parser, (byte) 0x6F, 2001);

        assertTrue(result.isError());
        assertEquals(LLPError.TIMEOUT, result.error().orElseThrow());
        assertEquals(1, parser.statistics().timeouts());
        assertEquals(1, parser.statistics().framesError());
    }

    @Test
    void aGapEqualToTheLimitIsNotATimeout() {
        byte[] frame = LLPEncoder.encode(0, ascii("hello"));
        LLPParser parser = parser();
        Collector c = new Collector();

        for (int i = 0; i < 4; i++) {
            c.feed(parser, frame[i], 0);
        }
        for (int i = 4; i < frame.length; i++) {
            c.feed(parser, frame[i], 2000);
        }

        assertTrue(c.errors.isEmpty(), "the comparison is strictly greater than the limit");
        assertEquals(1, c.payloads.size());
        assertArrayEquals(ascii("hello"), c.payloads.get(0));
    }

    @Test
    void aGapOneMillisecondOverTheLimitIsATimeout() {
        byte[] frame = LLPEncoder.encode(0, ascii("hello"));
        LLPParser parser = parser();
        Collector c = new Collector();

        for (int i = 0; i < 4; i++) {
            c.feed(parser, frame[i], 0);
        }
        for (int i = 4; i < frame.length; i++) {
            c.feed(parser, frame[i], 2001);
        }

        assertEquals(List.of(LLPError.TIMEOUT), c.errors);
        assertTrue(c.payloads.isEmpty());
    }

    @Test
    void anIdleLinkBeforeTheMagicNeverTimesOut() {
        byte[] frame = LLPEncoder.encode(0, ascii("ok\n"));
        LLPParser parser = parser();
        Collector c = new Collector();

        c.feed(parser, (byte) 0x00, 0);
        c.feed(parser, frame, 100_000);

        assertTrue(c.errors.isEmpty());
        assertEquals(1, c.payloads.size());
    }

    @Test
    void theByteAfterATimeoutIsReexaminedAsMagic() {
        LLPParser parser = parser();
        Collector c = new Collector();
        c.feed(parser, new byte[]{(byte) 0xAA, 0x55, 0x05, 0x00, 0x68}, 0);

        byte[] next = LLPEncoder.encode(1, ascii("after-timeout"));
        c.feed(parser, next, 2001);

        assertEquals(List.of(LLPError.TIMEOUT), c.errors);
        assertEquals(1, c.payloads.size(), "the frame after the gap must not be lost");
        assertEquals(1, c.channels.get(0));
        assertArrayEquals(ascii("after-timeout"), c.payloads.get(0));
    }

    @Test
    void timestampWrapDoesNotProduceATimeoutForShortGaps() {
        byte[] frame = LLPEncoder.encode(0, ascii("ok\n"));
        LLPParser parser = parser();
        Collector c = new Collector();

        c.feed(parser, (byte) 0x00, 65530);
        int now = 65530;
        for (byte b : frame) {
            now++;
            c.feed(parser, b, now);
        }

        assertTrue(c.errors.isEmpty(), "a 1 ms gap across the 16-bit wrap is not a timeout");
        assertEquals(1, c.payloads.size());
    }

    @Test
    void timestampWrapStillDetectsALongGap() {
        byte[] frame = LLPEncoder.encode(0, ascii("ok\n"));
        LLPParser parser = parser();
        Collector c = new Collector();

        c.feed(parser, (byte) 0x00, 65530);
        c.feed(parser, frame[0], 65531);
        c.feed(parser, frame[1], 65532);
        c.feed(parser, frame[2], 65533);
        c.feed(parser, frame[3], 65534);
        c.feed(parser, frame[4], 65535);

        FeedResult result = c.feed(parser, frame[5], 65535 + 2001);

        assertEquals(List.of(LLPError.TIMEOUT), c.errors);
        assertTrue(result.isError());
        assertTrue(c.payloads.isEmpty());
    }

    // ------------------------------------------------------------------
    // Storage, reset and configuration
    // ------------------------------------------------------------------

    @Test
    void writesIntoTheCallersBuffer() {
        byte[] storage = new byte[4];
        LLPParser parser = new LLPParser(storage, 2000);
        Collector c = new Collector();
        c.feed(parser, LLPEncoder.encode(0, ascii("abcd")), 0);

        assertArrayEquals(ascii("abcd"), storage);
        assertArrayEquals(ascii("abcd"), c.payloads.get(0));
    }

    @Test
    void thePayloadViewIsReadOnly() {
        LLPParser parser = parser();
        parser.feed((byte) 0, 0);
        for (byte b : LLPEncoder.encode(0, ascii("x"))) {
            parser.feed(b, 0);
        }

        ByteBuffer payload = parser.payload();
        assertTrue(payload.isReadOnly());
        assertThrows(ReadOnlyBufferException.class, () -> payload.put(0, (byte) 0x00));
    }

    @Test
    void statisticsAccumulateAcrossFrames() {
        LLPParser parser = parser();
        Collector c = new Collector();

        c.feed(parser, LLPEncoder.encode(0, ascii("a")), 0);
        c.feed(parser, LLPEncoder.encode(0, ascii("b")), 0);
        byte[] corrupt = LLPEncoder.encode(0, ascii("c"));
        corrupt[corrupt.length - 1] ^= 0xFF;
        c.feed(parser, corrupt, 0);

        assertEquals(2, parser.statistics().framesOk());
        assertEquals(1, parser.statistics().framesError());
        assertEquals(0, parser.statistics().timeouts());
    }

    @Test
    void resetKeepsTheConfiguration() {
        LLPParser parser = new LLPParser(8, 1500);
        Collector c = new Collector();

        c.feed(parser, new byte[]{(byte) 0xAA, 0x55, 0x03, 0x00}, 0);
        parser.reset();
        c.feed(parser, LLPEncoder.encode(0, ascii("abcd")), 100);

        assertEquals(8, parser.capacity());
        assertEquals(1500, parser.timeoutMs());
        assertEquals(1, c.payloads.size());
        assertArrayEquals(ascii("abcd"), c.payloads.get(0));
    }

    @Test
    void discardDropsTheFrameInProgress() {
        LLPParser parser = parser();
        Collector c = new Collector();

        c.feed(parser, new byte[]{(byte) 0xAA, 0x55, 0x05, 0x00, 0x61}, 0);
        parser.discard();
        c.feed(parser, LLPEncoder.encode(3, ascii("ok")), 10);

        assertEquals(1, c.payloads.size());
        assertEquals(3, c.channels.get(0));
    }

    @Test
    void errorAccessorMirrorsTheLastFeed() {
        LLPParser parser = parser();
        assertEquals(FeedResult.NONE.error(), parser.error());

        byte[] frame = LLPEncoder.encode(0, ascii("ok"));
        frame[frame.length - 1] ^= 0xFF;
        for (byte b : frame) {
            parser.feed(b, 0);
        }

        assertEquals(LLPError.CRC, parser.error().orElseThrow());
    }

    @Test
    void constructorRejectsInvalidConfiguration() {
        assertThrows(LLPException.class, () -> new LLPParser(-1, 2000));
        assertThrows(LLPException.class, () -> new LLPParser(256, 2000));
        assertThrows(LLPException.class, () -> new LLPParser(0, 0));
        assertThrows(LLPException.class, () -> new LLPParser(0, 32768));
        assertThrows(LLPException.class, () -> new LLPParser((byte[]) null, 2000));
        assertThrows(LLPException.class, () -> new LLPParser(new byte[256], 2000));
    }

    @Test
    void feedOfAFrameReturnsTheSharedSingleton() {
        LLPParser parser = parser();
        FeedResult result = FeedResult.NONE;
        for (byte b : LLPEncoder.encode(0, new byte[0])) {
            result = parser.feed(b, 0);
        }
        assertSame(FeedResult.FRAME, result);
        assertFalse(result.isError());
        assertTrue(result.error().isEmpty());
    }
}
