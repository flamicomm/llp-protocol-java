package com.flamingo.comm.llp;

import com.flamingo.comm.llp.util.CRC16CCITT;

import java.util.Arrays;

/**
 * Streaming encoder for LLP wire format 4.0.0.
 *
 * <p>The Java counterpart of {@code llp4_tx_t}: the frame is written to a
 * {@link Sink} as the payload is produced, so no frame-sized buffer is needed
 * (useful when the payload is itself generated on the fly).</p>
 *
 * <pre>{@code
 * LLPEncoder encoder = new LLPEncoder(channel, payload.length, port::write);
 * for (byte b : payload) {
 *     encoder.payloadByte(b);
 * }
 * encoder.end();
 * }</pre>
 *
 * <p>{@link #encode(int, byte[])} is the convenience path when the payload is
 * already in memory. Instances are single-use and not thread-safe.</p>
 */
public final class LLPEncoder {

    /** Receives the bytes of the frame, in order. */
    @FunctionalInterface
    public interface Sink {
        /**
         * Writes one byte to the link.
         *
         * @param b the byte
         */
        void write(byte b);
    }

    private static final int MAGIC1 = 0xAA;
    private static final int MAGIC2 = 0x55;
    private static final int MAX_PAYLOAD = 255;

    private final Sink sink;
    private final int payloadLength;
    private int remaining;
    private int crc;

    /**
     * Starts a frame: writes the magic, {@code LEN} and {@code CHANNEL}.
     *
     * @param channel       routing channel, 0..255
     * @param payloadLength number of payload bytes that will follow, 0..255
     * @param sink          receiver of the encoded bytes
     * @throws LLPException if a value is out of range or the sink is null
     */
    public LLPEncoder(int channel, int payloadLength, Sink sink) {
        if (channel < 0 || channel > 255) {
            throw new LLPException("channel out of range 0..255: " + channel);
        }
        if (payloadLength < 0 || payloadLength > MAX_PAYLOAD) {
            throw new LLPException("payload length out of range 0..255: " + payloadLength);
        }
        if (sink == null) {
            throw new LLPException("sink cannot be null");
        }
        this.sink = sink;
        this.payloadLength = payloadLength;
        this.remaining = payloadLength;

        sink.write((byte) MAGIC1);
        sink.write((byte) MAGIC2);

        int running = CRC16CCITT.updateCRC(CRC16CCITT.updateCRC(0xFFFF, (byte) MAGIC1), (byte) MAGIC2);
        running = CRC16CCITT.updateCRC(running, (byte) payloadLength);
        writeStuffed(payloadLength);
        running = CRC16CCITT.updateCRC(running, (byte) channel);
        writeStuffed(channel);
        this.crc = running;
    }

    /**
     * Writes one payload byte (stuffed and folded into the CRC).
     *
     * @param b the payload byte
     * @throws LLPException if more bytes than declared are written
     */
    public void payloadByte(byte b) {
        if (remaining == 0) {
            throw new LLPException("payload of " + payloadLength + " byte(s) is already complete");
        }
        writeStuffed(b & 0xFF);
        crc = CRC16CCITT.updateCRC(crc, b);
        remaining--;
    }

    /**
     * Writes the CRC and closes the frame.
     *
     * @throws LLPException if payload bytes are still missing
     */
    public void end() {
        if (remaining != 0) {
            throw new LLPException("frame incomplete: " + remaining + " payload byte(s) missing");
        }
        writeStuffed(crc & 0xFF);
        writeStuffed((crc >> 8) & 0xFF);
    }

    /**
     * Worst-case on-the-wire size of a frame with {@code payloadLength} bytes,
     * when every byte after the magic needs stuffing.
     *
     * @param payloadLength 0..255
     * @return {@code 10 + 2 * payloadLength}
     * @throws LLPException if the length is out of range
     */
    public static int frameSize(int payloadLength) {
        if (payloadLength < 0 || payloadLength > MAX_PAYLOAD) {
            throw new LLPException("payload length out of range 0..255: " + payloadLength);
        }
        return 10 + (2 * payloadLength);
    }

    /**
     * Encodes a complete frame into a new array.
     *
     * @param channel routing channel, 0..255
     * @param payload payload bytes
     * @return the exact frame bytes
     * @throws LLPException if an argument is out of range
     */
    public static byte[] encode(int channel, byte[] payload) {
        if (payload == null) {
            throw new LLPException("payload cannot be null");
        }
        return encode(channel, payload, 0, payload.length);
    }

    /**
     * Encodes a slice of an array into a new frame.
     *
     * @param channel routing channel, 0..255
     * @param payload source array
     * @param offset  first byte to encode
     * @param length  number of bytes to encode, 0..255
     * @return the exact frame bytes
     * @throws LLPException if an argument is out of range
     */
    public static byte[] encode(int channel, byte[] payload, int offset, int length) {
        if (payload == null) {
            throw new LLPException("payload cannot be null");
        }
        if (offset < 0 || length < 0 || offset + length > payload.length) {
            throw new LLPException("payload range out of bounds: offset=" + offset + " length=" + length);
        }
        byte[] buffer = new byte[frameSize(length)];
        int[] position = {0};
        LLPEncoder encoder = new LLPEncoder(channel, length, b -> buffer[position[0]++] = b);
        for (int i = 0; i < length; i++) {
            encoder.payloadByte(payload[offset + i]);
        }
        encoder.end();
        return Arrays.copyOf(buffer, position[0]);
    }

    private void writeStuffed(int value) {
        byte b = (byte) value;
        sink.write(b);
        if ((value & 0xFF) == MAGIC1) {
            sink.write((byte) 0x00);
        }
    }
}
