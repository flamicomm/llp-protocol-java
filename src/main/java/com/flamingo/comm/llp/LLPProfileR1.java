package com.flamingo.comm.llp;

import java.util.ArrayList;
import java.util.List;

/**
 * LLP v4 <strong>Profile R1</strong>: reliable command channel.
 *
 * <p>The optional profile defined in llp-spec {@code docs/RELIABILITY.md}. It
 * adds at-most-once delivery for commands on top of the v4 framing, without
 * changing the wire format:</p>
 *
 * <pre>
 * COMMAND (channel 1):  [SEQ][DATA...]      sender -&gt; receiver
 * ACK     (channel 2):  [SEQ]               receiver -&gt; sender
 * HELLO   (channel 3):  [SESSION]           sender -&gt; receiver, resets dedup
 * </pre>
 *
 * <p>This class holds the constants and the payload helpers.
 * {@link LLPRelaySender} and {@link LLPRelayReceiver} implement the two state
 * machines; framing stays in {@link LLP} / {@link LLPParser}.</p>
 *
 * @see LLPRelaySender
 * @see LLPRelayReceiver
 */
public final class LLPProfileR1 {

    /** Reliable command frames (sender to receiver). */
    public static final int CH_COMMAND = 1;

    /** Acknowledgements (receiver to sender). */
    public static final int CH_ACK = 2;

    /**
     * Session marker (sender to receiver): the duplicate-suppression state must
     * be reset before the first command of a new session.
     */
    public static final int CH_HELLO = 3;

    /** Maximum {@code DATA} bytes in one command frame. */
    public static final int MAX_DATA = 63;

    /** Maximum command payload, including the sequence byte. */
    public static final int MAX_PAYLOAD = 1 + MAX_DATA;

    /** Recommended ACK timeout. */
    public static final int DEFAULT_ACK_TIMEOUT_MS = 50;

    /** Recommended number of fast retransmissions. */
    public static final int DEFAULT_MAX_RETRIES = 5;

    /** Recommended poll period once the fast retries are spent. */
    public static final int DEFAULT_BACKOFF_MS = 1000;

    /** Default session marker carried by {@link #helloPayload()}. */
    public static final int DEFAULT_SESSION = 0x52;

    private LLPProfileR1() {
    }

    /**
     * Builds a command payload: {@code [SEQ][DATA]}.
     *
     * @param seq  sequence number, masked to 0..255
     * @param data application bytes, at most {@link #MAX_DATA}
     * @return the payload to encode on {@link #CH_COMMAND}
     * @throws IllegalArgumentException if {@code data} is null or too long
     */
    public static byte[] commandPayload(int seq, byte[] data) {
        if (data == null) {
            throw new IllegalArgumentException("data cannot be null");
        }
        if (data.length > MAX_DATA) {
            throw new IllegalArgumentException(
                    "data too long for one frame: " + data.length + " > " + MAX_DATA);
        }
        byte[] payload = new byte[1 + data.length];
        payload[0] = (byte) (seq & 0xFF);
        System.arraycopy(data, 0, payload, 1, data.length);
        return payload;
    }

    /**
     * Builds an acknowledgement payload: {@code [SEQ]}.
     *
     * @param seq sequence number being acknowledged
     * @return the payload to encode on {@link #CH_ACK}
     */
    public static byte[] ackPayload(int seq) {
        return new byte[]{(byte) (seq & 0xFF)};
    }

    /**
     * Builds a session marker: {@code [SESSION]}.
     *
     * @param session opaque session byte
     * @return the payload to encode on {@link #CH_HELLO}
     */
    public static byte[] helloPayload(int session) {
        return new byte[]{(byte) (session & 0xFF)};
    }

    /**
     * Builds the default session marker.
     *
     * @return the payload to encode on {@link #CH_HELLO}
     */
    public static byte[] helloPayload() {
        return helloPayload(DEFAULT_SESSION);
    }

    /**
     * Splits a message into consecutive {@code DATA} pieces (profile §5.3).
     *
     * @param data message bytes
     * @return one or more pieces of at most {@link #MAX_DATA} bytes
     */
    public static List<byte[]> chunks(byte[] data) {
        if (data == null) {
            throw new IllegalArgumentException("data cannot be null");
        }
        List<byte[]> out = new ArrayList<>(Math.max(1, (data.length + MAX_DATA - 1) / MAX_DATA));
        for (int offset = 0; offset < data.length; offset += MAX_DATA) {
            int size = Math.min(MAX_DATA, data.length - offset);
            byte[] piece = new byte[size];
            System.arraycopy(data, offset, piece, 0, size);
            out.add(piece);
        }
        return out;
    }
}
