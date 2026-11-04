package com.flamingo.comm.llp;

import com.flamingo.comm.llp.util.CRC16CCITT;
import com.flamingo.comm.llp.util.Statistics;

import java.nio.ByteBuffer;
import java.util.Optional;

/**
 * Byte-at-a-time parser for LLP wire format 4.0.0.
 *
 * <p>This is the Java counterpart of the C {@code llp4_parser_t}: the payload
 * buffer belongs to the caller, the time is supplied by the caller, and no
 * allocation happens per byte.</p>
 *
 * <h2>Contract</h2>
 * <ul>
 *     <li>{@link #feed(byte, int)} returns {@link FeedResult#frame()} only after
 *     the CRC validates. No payload byte is observable before that.</li>
 *     <li>Work per byte is O(1); there is no loop over the payload.</li>
 *     <li>{@link #payload()} is a view over the caller's buffer and stays valid
 *     until the next {@code feed} call.</li>
 *     <li>Time is injected: {@code nowMs} is reduced modulo 65536 and compared
 *     with unsigned arithmetic, so it is wrap-safe. The maximum timeout is
 *     {@value #MAX_TIMEOUT_MS} ms.</li>
 * </ul>
 *
 * <h2>Example</h2>
 * <pre>{@code
 * LLPParser parser = new LLPParser(255, 2000);
 * for (byte b : chunk) {
 *     if (parser.feed(b, nowMs) instanceof FeedResult.Frame) {
 *         handle(parser.channel(), parser.payload());
 *     }
 * }
 * }</pre>
 *
 * <p>One parser per link, fed from one context: this class is not thread-safe.</p>
 */
public final class LLPParser {

    /** Maximum payload capacity of a single frame. */
    public static final int MAX_CAPACITY = 255;

    /** Maximum supported inter-byte timeout (16-bit wrap-safe arithmetic). */
    public static final int MAX_TIMEOUT_MS = 32767;

    /** Suggested inter-byte timeout. */
    public static final int DEFAULT_TIMEOUT_MS = 2000;

    private static final int MAGIC1 = 0xAA;
    private static final int MAGIC2 = 0x55;

    private static final int WAIT_MAGIC1 = 0;
    private static final int WAIT_MAGIC2 = 1;
    private static final int READ_LEN = 2;
    private static final int READ_CHANNEL = 3;
    private static final int READ_PAYLOAD = 4;
    private static final int READ_CRC_L = 5;
    private static final int READ_CRC_H = 6;

    private final byte[] storage;
    private final int capacity;
    private final int timeoutMs;
    private final Statistics statistics = new Statistics();

    private int state;
    private boolean escapePending;
    private int length;
    private int channel;
    private int index;
    private int crcCalculated;
    private int crcReceived;
    private int lastByteTime;
    private LLPError error;

    /**
     * Creates a parser with its own payload buffer.
     *
     * @param capacity  payload capacity, 0..{@value #MAX_CAPACITY}
     * @param timeoutMs inter-byte timeout, 1..{@value #MAX_TIMEOUT_MS}
     * @throws LLPException if either value is out of range
     */
    public LLPParser(int capacity, int timeoutMs) {
        this(allocate(capacity), timeoutMs);
    }

    /**
     * Creates a parser that writes payloads into a caller-owned buffer, exactly
     * like {@code llp4_parser_init(p, buf, cap, timeout)}.
     *
     * @param storage   payload storage, length 0..{@value #MAX_CAPACITY}
     * @param timeoutMs inter-byte timeout, 1..{@value #MAX_TIMEOUT_MS}
     * @throws LLPException if the storage or the timeout is invalid
     */
    public LLPParser(byte[] storage, int timeoutMs) {
        if (storage == null) {
            throw new LLPException("payload storage cannot be null");
        }
        if (storage.length > MAX_CAPACITY) {
            throw new LLPException("payload storage exceeds " + MAX_CAPACITY + " bytes: " + storage.length);
        }
        if (timeoutMs < 1 || timeoutMs > MAX_TIMEOUT_MS) {
            throw new LLPException("timeoutMs out of range 1.." + MAX_TIMEOUT_MS + ": " + timeoutMs);
        }
        this.storage = storage;
        this.capacity = storage.length;
        this.timeoutMs = timeoutMs;
        reset();
    }

    private static byte[] allocate(int capacity) {
        if (capacity < 0 || capacity > MAX_CAPACITY) {
            throw new LLPException("capacity out of range 0.." + MAX_CAPACITY + ": " + capacity);
        }
        return new byte[capacity];
    }

    /**
     * Feeds one byte.
     *
     * @param b     the byte
     * @param nowMs current time in milliseconds; only differences modulo 65536 matter
     * @return {@link FeedResult#frame()} when a frame was delivered,
     *         {@link FeedResult#none()} otherwise, or an error result
     */
    public FeedResult feed(byte b, int nowMs) {
        int now = nowMs & 0xFFFF;
        int value = b & 0xFF;
        error = null;

        // Inter-byte timeout. Never while waiting for the magic: an idle link is normal.
        if (state != WAIT_MAGIC1 && (((now - lastByteTime) & 0xFFFF) > timeoutMs)) {
            fail(LLPError.TIMEOUT);
            lastByteTime = now;
            if (value == MAGIC1) {
                state = WAIT_MAGIC2;
            }
            return FeedResult.error(LLPError.TIMEOUT);
        }

        lastByteTime = now;

        // Escape handling, only inside a frame.
        if (state > WAIT_MAGIC2) {
            if (escapePending) {
                escapePending = false;
                if (value == MAGIC2) {
                    // A new magic aborted the frame: report and start over with it.
                    statistics.recordError();
                    beginFrame();
                    error = LLPError.SYNC;
                    return FeedResult.error(LLPError.SYNC);
                }
                if (value == 0x00) {
                    value = MAGIC1;
                } else {
                    return fail(LLPError.SYNC);
                }
            } else if (value == MAGIC1) {
                escapePending = true;
                return FeedResult.none();
            }
        }

        switch (state) {
            case WAIT_MAGIC1 -> {
                if (value == MAGIC1) {
                    state = WAIT_MAGIC2;
                }
            }
            case WAIT_MAGIC2 -> {
                if (value == MAGIC2) {
                    beginFrame();
                } else if (value != MAGIC1) {
                    state = WAIT_MAGIC1;
                }
            }
            case READ_LEN -> {
                if (value > capacity) {
                    return fail(LLPError.LEN);
                }
                length = value;
                crcCalculated = CRC16CCITT.updateCRC(crcCalculated, (byte) value);
                state = READ_CHANNEL;
            }
            case READ_CHANNEL -> {
                channel = value;
                crcCalculated = CRC16CCITT.updateCRC(crcCalculated, (byte) value);
                state = (length == 0) ? READ_CRC_L : READ_PAYLOAD;
            }
            case READ_PAYLOAD -> {
                storage[index++] = (byte) value;
                crcCalculated = CRC16CCITT.updateCRC(crcCalculated, (byte) value);
                if (index == length) {
                    state = READ_CRC_L;
                }
            }
            case READ_CRC_L -> {
                crcReceived = value;
                state = READ_CRC_H;
            }
            case READ_CRC_H -> {
                crcReceived |= (value << 8);
                if (crcReceived != crcCalculated) {
                    return fail(LLPError.CRC);
                }
                statistics.recordSuccess();
                state = WAIT_MAGIC1;
                escapePending = false;
                return FeedResult.frame();
            }
            default -> throw new IllegalStateException("unknown parser state: " + state);
        }

        return FeedResult.none();
    }

    /**
     * Drops the frame in progress and returns to {@code WAIT_MAGIC1}, keeping the
     * configuration (buffer, capacity, timeout).
     */
    public void discard() {
        state = WAIT_MAGIC1;
        escapePending = false;
        index = 0;
        length = 0;
        channel = 0;
    }

    /**
     * Full reset: discards the frame, resets the CRC and the clock baseline.
     * Cumulative {@link #statistics()} are not cleared.
     */
    public void reset() {
        discard();
        crcCalculated = crcSeed();
        crcReceived = 0;
        lastByteTime = 0;
        error = null;
    }

    /**
     * Payload of the last delivered frame.
     *
     * <p>The buffer is a read-only view over the parser storage and is only
     * meaningful after {@link FeedResult#frame()}; it stays valid until the next
     * {@link #feed(byte, int)} call.</p>
     *
     * @return a read-only view, length {@link #length()}
     */
    public ByteBuffer payload() {
        return ByteBuffer.wrap(storage, 0, length).asReadOnlyBuffer();
    }

    /**
     * Channel of the last delivered frame.
     *
     * @return 0..255
     */
    public int channel() {
        return channel;
    }

    /**
     * Payload length of the last delivered frame.
     *
     * @return 0..255
     */
    public int length() {
        return length;
    }

    /**
     * CRC that validated the last delivered frame.
     *
     * @return the CRC16-CCITT value
     */
    public int crc() {
        return crcCalculated;
    }

    /**
     * Error reported by the last {@link #feed(byte, int)} call.
     *
     * @return the error, or empty if the last call was clean
     */
    public Optional<LLPError> error() {
        return Optional.ofNullable(error);
    }

    /**
     * Cumulative counters for this parser instance.
     *
     * @return the live statistics object
     */
    public Statistics statistics() {
        return statistics;
    }

    /**
     * Configured payload capacity.
     *
     * @return 0..255
     */
    public int capacity() {
        return capacity;
    }

    /**
     * Configured inter-byte timeout.
     *
     * @return milliseconds
     */
    public int timeoutMs() {
        return timeoutMs;
    }

    private static int crcSeed() {
        return CRC16CCITT.updateCRC(CRC16CCITT.updateCRC(0xFFFF, (byte) MAGIC1), (byte) MAGIC2);
    }

    private void beginFrame() {
        state = READ_LEN;
        escapePending = false;
        length = 0;
        channel = 0;
        index = 0;
        crcCalculated = crcSeed();
    }

    private FeedResult fail(LLPError code) {
        statistics.recordError();
        if (code == LLPError.TIMEOUT) {
            statistics.recordTimeout();
        }
        error = code;
        discard();
        return FeedResult.error(code);
    }
}
