package com.flamingo.comm.llp;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Pull-based streaming receiver for LLP 4.0.0.
 *
 * <p>A thin convenience layer over {@link LLPParser}: bytes are pushed in, and
 * the frames and errors that appeared are retrieved by polling. Because v4 has
 * no layer chain, a delivered frame is already the final object.</p>
 *
 * <p>Time is injectable. The no-argument {@code feed} methods read the configured
 * clock; the overloads that take {@code nowMs} are deterministic and are what the
 * conformance tests use.</p>
 *
 * <pre>{@code
 * LLPIncrementalParser receiver = LLP.incrementalParser().build();
 * receiver.feed(serialRead());
 * for (LLPFrame frame : receiver.pollFrames()) {
 *     handle(frame.channel(), frame.payload());
 * }
 * }</pre>
 *
 * <p>Instances are not thread-safe.</p>
 */
public final class LLPIncrementalParser {

    private final LLPParser parser;
    private final LongSupplier clock;

    private final List<LLPFrame> frames = new ArrayList<>();
    private final List<LLPError> errors = new ArrayList<>();

    LLPIncrementalParser(int capacity, int timeoutMs, LongSupplier clock) {
        this.parser = new LLPParser(capacity, timeoutMs);
        this.clock = Objects.requireNonNull(clock, "clock cannot be null");
    }

    // ------------------------------------------------------------------
    // Input
    // ------------------------------------------------------------------

    /**
     * Feeds one byte, timestamped with the configured clock.
     *
     * @param b the byte
     */
    public void feed(byte b) {
        feed(b, (int) clock.getAsLong());
    }

    /**
     * Feeds one byte with an explicit timestamp.
     *
     * @param b     the byte
     * @param nowMs time in milliseconds; only differences modulo 65536 matter
     */
    public void feed(byte b, int nowMs) {
        handle(parser.feed(b, nowMs));
    }

    /**
     * Feeds a whole chunk, timestamped with the configured clock. Every byte of
     * the chunk shares the same timestamp.
     *
     * @param data the bytes
     */
    public void feed(byte[] data) {
        feed(data, (int) clock.getAsLong());
    }

    /**
     * Feeds a whole chunk with an explicit timestamp.
     *
     * @param data  the bytes
     * @param nowMs time in milliseconds
     */
    public void feed(byte[] data, int nowMs) {
        for (byte b : data) {
            handle(parser.feed(b, nowMs));
        }
    }

    /**
     * Feeds the remaining bytes of a buffer without consuming it.
     *
     * @param buffer source buffer, read from its current position to its limit
     * @param nowMs  time in milliseconds
     */
    public void feed(ByteBuffer buffer, int nowMs) {
        ByteBuffer view = buffer.duplicate();
        while (view.hasRemaining()) {
            handle(parser.feed(view.get(), nowMs));
        }
    }

    // ------------------------------------------------------------------
    // Output
    // ------------------------------------------------------------------

    /**
     * Returns the frames delivered since the previous poll and clears the queue.
     *
     * @return immutable list, newest last; never null
     */
    public List<LLPFrame> pollFrames() {
        List<LLPFrame> out = List.copyOf(frames);
        frames.clear();
        return out;
    }

    /**
     * Returns the errors reported since the previous poll and clears the queue.
     *
     * @return immutable list, in detection order; never null
     */
    public List<LLPError> pollErrors() {
        List<LLPError> out = List.copyOf(errors);
        errors.clear();
        return out;
    }

    /**
     * The underlying parser, for statistics or manual control.
     *
     * @return the parser instance
     */
    public LLPParser parser() {
        return parser;
    }

    private void handle(FeedResult result) {
        if (result instanceof FeedResult.Frame) {
            frames.add(new LLPFrame(
                    parser.channel(),
                    parser.payload(),
                    parser.crc(),
                    clock.getAsLong()));
        } else if (result instanceof FeedResult.Error e) {
            errors.add(e.value());
        }
    }
}
