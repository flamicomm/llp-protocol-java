package com.flamingo.comm.llp;

import java.util.function.LongSupplier;

/**
 * Entry point and factory facade for LLP wire format 4.0.0.
 *
 * <p>Three things can be built:</p>
 * <ul>
 *     <li>{@link #parser()} — the byte-at-a-time {@link LLPParser} (mirror of the C core)</li>
 *     <li>{@link #incrementalParser()} — the pull-based {@link LLPIncrementalParser}</li>
 *     <li>{@link #encode(int, byte[])} / {@link #frameSize(int)} — the encoder</li>
 * </ul>
 *
 * <pre>{@code
 * LLPParser parser = LLP.parser().capacity(255).timeoutMs(2000).build();
 * byte[] frame = LLP.encode(1, "G1 X10\n".getBytes(StandardCharsets.US_ASCII));
 * }</pre>
 */
public final class LLP {

    /** Maximum payload capacity of a parser. */
    public static final int MAX_CAPACITY = LLPParser.MAX_CAPACITY;

    /** Suggested inter-byte timeout. */
    public static final int DEFAULT_TIMEOUT_MS = LLPParser.DEFAULT_TIMEOUT_MS;

    private LLP() {
    }

    /**
     * Starts configuring a byte-at-a-time parser.
     *
     * @return a new builder
     */
    public static ParserBuilder parser() {
        return new ParserBuilder();
    }

    /**
     * Starts configuring a pull-based streaming receiver.
     *
     * @return a new builder
     */
    public static IncrementalParserBuilder incrementalParser() {
        return new IncrementalParserBuilder();
    }

    /**
     * Encodes one frame.
     *
     * @param channel routing channel, 0..255
     * @param payload payload bytes, at most 255
     * @return the exact frame bytes
     * @throws LLPException if an argument is out of range
     */
    public static byte[] encode(int channel, byte[] payload) {
        return LLPEncoder.encode(channel, payload);
    }

    /**
     * Worst-case wire size of a frame with the given payload length.
     *
     * @param payloadLength 0..255
     * @return {@code 10 + 2 * payloadLength}
     * @throws LLPException if the length is out of range
     */
    public static int frameSize(int payloadLength) {
        return LLPEncoder.frameSize(payloadLength);
    }

    /**
     * Builder for {@link LLPParser}.
     */
    public static final class ParserBuilder {

        private int capacity = LLPParser.MAX_CAPACITY;
        private int timeoutMs = LLPParser.DEFAULT_TIMEOUT_MS;

        private ParserBuilder() {
        }

        /**
         * Sets the payload capacity.
         *
         * @param bytes 0..255
         * @return this builder
         */
        public ParserBuilder capacity(int bytes) {
            this.capacity = bytes;
            return this;
        }

        /**
         * Sets the inter-byte timeout.
         *
         * @param millis 1..32767
         * @return this builder
         */
        public ParserBuilder timeoutMs(int millis) {
            this.timeoutMs = millis;
            return this;
        }

        /**
         * Builds the parser.
         *
         * @return a new parser
         * @throws LLPException if the configuration is out of range
         */
        public LLPParser build() {
            return new LLPParser(capacity, timeoutMs);
        }
    }

    /**
     * Builder for {@link LLPIncrementalParser}.
     */
    public static final class IncrementalParserBuilder {

        private int capacity = LLPParser.MAX_CAPACITY;
        private int timeoutMs = LLPParser.DEFAULT_TIMEOUT_MS;
        private LongSupplier clock = System::currentTimeMillis;

        private IncrementalParserBuilder() {
        }

        /**
         * Sets the payload capacity.
         *
         * @param bytes 0..255
         * @return this builder
         */
        public IncrementalParserBuilder capacity(int bytes) {
            this.capacity = bytes;
            return this;
        }

        /**
         * Sets the inter-byte timeout.
         *
         * @param millis 1..32767
         * @return this builder
         */
        public IncrementalParserBuilder timeoutMs(int millis) {
            this.timeoutMs = millis;
            return this;
        }

        /**
         * Sets the clock used by the no-argument {@code feed} methods.
         *
         * @param supplier millisecond source
         * @return this builder
         */
        public IncrementalParserBuilder clock(LongSupplier supplier) {
            this.clock = supplier;
            return this;
        }

        /**
         * Builds the receiver.
         *
         * @return a new receiver
         * @throws LLPException if the configuration is out of range
         */
        public LLPIncrementalParser build() {
            return new LLPIncrementalParser(capacity, timeoutMs, clock);
        }
    }
}
