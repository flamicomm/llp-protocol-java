package com.flamingo.comm.llp;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behaviour of the pull-based receiver: chunked input, injected clock, and the
 * polling contract (each frame and error is returned exactly once).
 */
class LLPIncrementalParserTest {

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    private static LLPIncrementalParser receiver(int capacity, int timeoutMs) {
        return LLP.incrementalParser().capacity(capacity).timeoutMs(timeoutMs).build();
    }

    @Test
    void deliversFramesWithChannelAndPayload() {
        LLPIncrementalParser receiver = receiver(255, 2000);
        receiver.feed(LLPEncoder.encode(2, ascii("telemetry")), 0);

        List<LLPFrame> frames = receiver.pollFrames();
        assertEquals(1, frames.size());
        assertEquals(2, frames.getFirst().channel());
        assertArrayEquals(ascii("telemetry"), frames.getFirst().payloadBytes());
        assertEquals(9, frames.getFirst().length());
    }

    @Test
    void chunkBoundariesDoNotMatter() {
        byte[] frame = LLPEncoder.encode(0, ascii("hello"));
        LLPIncrementalParser receiver = receiver(255, 2000);

        receiver.feed(new byte[]{frame[0]}, 0);
        receiver.feed(new byte[]{frame[1], frame[2]}, 0);
        receiver.feed(java.util.Arrays.copyOfRange(frame, 3, frame.length), 0);

        assertEquals(1, receiver.pollFrames().size());
        assertTrue(receiver.pollErrors().isEmpty());
    }

    @Test
    void deliversSeveralFramesInOneChunk() {
        byte[] chunk = new byte[0];
        chunk = concat(chunk, LLPEncoder.encode(0, ascii("a")));
        chunk = concat(chunk, LLPEncoder.encode(1, ascii("b")));
        chunk = concat(chunk, LLPEncoder.encode(255, ascii("c")));

        LLPIncrementalParser receiver = receiver(255, 2000);
        receiver.feed(chunk, 0);

        List<LLPFrame> frames = receiver.pollFrames();
        assertEquals(3, frames.size());
        assertEquals(0, frames.get(0).channel());
        assertEquals(1, frames.get(1).channel());
        assertEquals(255, frames.get(2).channel());
    }

    @Test
    void errorsAreReturnedOnce() {
        byte[] corrupt = LLPEncoder.encode(0, ascii("bad"));
        corrupt[corrupt.length - 1] ^= 0xFF;

        LLPIncrementalParser receiver = receiver(255, 2000);
        receiver.feed(corrupt, 0);

        assertEquals(List.of(LLPError.CRC), receiver.pollErrors());
        assertTrue(receiver.pollErrors().isEmpty(), "the queue must be cleared by poll");
        assertTrue(receiver.pollFrames().isEmpty());
    }

    @Test
    void theClockIsInjectable() {
        AtomicInteger ticks = new AtomicInteger(1234);
        LLPIncrementalParser receiver = LLP.incrementalParser()
                .clock(ticks::get)
                .build();

        receiver.feed(LLPEncoder.encode(0, ascii("x")));

        assertEquals(1234L, receiver.pollFrames().getFirst().receivedAt());
    }

    @Test
    void feedingABufferDoesNotConsumeIt() {
        byte[] frame = LLPEncoder.encode(0, ascii("ok"));
        ByteBuffer buffer = ByteBuffer.wrap(frame);

        LLPIncrementalParser receiver = receiver(255, 2000);
        receiver.feed(buffer, 0);

        assertEquals(1, receiver.pollFrames().size());
        assertEquals(0, buffer.position(), "the caller's buffer must not be consumed");
    }

    @Test
    void exposesTheUnderlyingParser() {
        LLPIncrementalParser receiver = receiver(32, 1500);
        assertEquals(32, receiver.parser().capacity());
        assertEquals(1500, receiver.parser().timeoutMs());
    }

    @Test
    void aTimeGapMidFrameIsReportedAsTimeout() {
        byte[] frame = LLPEncoder.encode(0, ascii("hello"));
        LLPIncrementalParser receiver = receiver(255, 2000);

        receiver.feed(java.util.Arrays.copyOfRange(frame, 0, 4), 0);
        receiver.feed(java.util.Arrays.copyOfRange(frame, 4, frame.length), 2500);

        assertEquals(List.of(LLPError.TIMEOUT), receiver.pollErrors());
        assertTrue(receiver.pollFrames().isEmpty());
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = java.util.Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
