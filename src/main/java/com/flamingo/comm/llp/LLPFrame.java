package com.flamingo.comm.llp;

import java.nio.ByteBuffer;

/**
 * A complete, CRC-validated LLP 4.0.0 frame.
 *
 * <p>In v4 the payload is opaque: there is no layer chain to parse, so this is
 * the final object an application sees. It carries the channel the frame was
 * routed on, the payload bytes, the validated CRC and a timestamp.</p>
 *
 * <p>Instances are immutable: the payload is copied on construction and exposed
 * as a read-only buffer.</p>
 *
 * @param channel    routing channel, 0..255
 * @param payload    payload bytes, read-only
 * @param crc        CRC16-CCITT value that validated the frame
 * @param receivedAt millisecond timestamp supplied by the caller's clock
 */
public record LLPFrame(int channel, ByteBuffer payload, int crc, long receivedAt) {

    /**
     * Compact constructor: validates the channel and takes a defensive copy of
     * the payload.
     *
     * @throws LLPException if the channel is outside {@code 0..255}
     */
    public LLPFrame {
        if (channel < 0 || channel > 255) {
            throw new LLPException("channel out of range 0..255: " + channel);
        }
        if (payload == null) {
            throw new LLPException("payload cannot be null");
        }
        ByteBuffer source = payload.duplicate();
        ByteBuffer copy = ByteBuffer.allocate(source.remaining());
        copy.put(source);
        copy.flip();
        payload = copy.asReadOnlyBuffer();
    }

    /**
     * Payload length in bytes.
     *
     * @return 0..255
     */
    public int length() {
        return payload.remaining();
    }

    /**
     * Returns a copy of the payload as a byte array.
     *
     * @return a new array with the payload bytes
     */
    public byte[] payloadBytes() {
        byte[] out = new byte[payload.remaining()];
        payload.duplicate().get(out);
        return out;
    }
}
