package com.flamingo.comm.llp;

/**
 * Sender side of {@link LLPProfileR1}: stop-and-wait window of one.
 *
 * <p>The sender loads one command, transmits it, and waits for the matching
 * ACK. On timeout it retransmits a <strong>byte-identical</strong> frame, so
 * the receiver can deduplicate by sequence number: losing an ACK never runs a
 * command twice. After {@code maxRetries} fast retransmissions it reports
 * {@link Service#EXHAUSTED} once per backoff period and never claims
 * delivery.</p>
 *
 * <pre>{@code
 * LLPRelaySender sender = new LLPRelaySender();
 * if (sender.start(seq, "G1 X0\n".getBytes(), nowMs)) {
 *     write(LLP.encode(LLPProfileR1.CH_COMMAND, sender.payload()));
 * }
 * switch (sender.service(nowMs)) {
 *     case SEND -> write(LLP.encode(LLPProfileR1.CH_COMMAND, sender.payload()));
 *     case EXHAUSTED -> report();
 *     case IDLE -> { }
 * }
 * }</pre>
 *
 * <p>Time is injected as milliseconds and compared with 16-bit wrap semantics,
 * exactly like the C and Python implementations.</p>
 */
public final class LLPRelaySender {

    /** What the caller must do after {@link #service(long)}. */
    public enum Service {
        /** Nothing to do: window idle or still waiting. */
        IDLE,
        /** Transmit (or retransmit) {@link #payload()} now. */
        SEND,
        /** Fast retries spent: report it; never claim delivery. */
        EXHAUSTED
    }

    private final int timeoutMs;
    private final int maxRetries;
    private final int backoffMs;

    private boolean active;
    private boolean exhausted;
    private int seq;
    private int retries;
    private long sentMs;
    private byte[] payload = new byte[0];

    /** Creates a sender with the recommended timers. */
    public LLPRelaySender() {
        this(LLPProfileR1.DEFAULT_ACK_TIMEOUT_MS,
                LLPProfileR1.DEFAULT_MAX_RETRIES,
                LLPProfileR1.DEFAULT_BACKOFF_MS);
    }

    /**
     * Creates a sender with explicit timers.
     *
     * @param timeoutMs  ACK timeout, must be positive
     * @param maxRetries fast retransmissions allowed, 0 or more
     * @param backoffMs  poll period after exhaustion, must be positive
     */
    public LLPRelaySender(int timeoutMs, int maxRetries, int backoffMs) {
        if (timeoutMs <= 0) {
            throw new IllegalArgumentException("timeoutMs must be positive");
        }
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries cannot be negative");
        }
        if (backoffMs <= 0) {
            throw new IllegalArgumentException("backoffMs must be positive");
        }
        this.timeoutMs = timeoutMs;
        this.maxRetries = maxRetries;
        this.backoffMs = backoffMs;
    }

    /**
     * Loads a command into the window.
     *
     * @param seq   sequence number, masked to 0..255
     * @param data  application bytes, at most {@link LLPProfileR1#MAX_DATA}
     * @param nowMs current time
     * @return true when the caller must transmit {@link #payload()} now;
     *         false when the window is busy or the data does not fit
     */
    public boolean start(int seq, byte[] data, long nowMs) {
        if (active) {
            return false;
        }
        if (data == null || data.length > LLPProfileR1.MAX_DATA) {
            return false;
        }
        this.payload = LLPProfileR1.commandPayload(seq, data);
        this.seq = seq & 0xFF;
        this.retries = 0;
        this.exhausted = false;
        this.sentMs = nowMs;
        this.active = true;
        return true;
    }

    /**
     * Drives the retransmission timer. Call it on every main-loop iteration.
     *
     * @param nowMs current time
     * @return {@link Service#SEND} to retransmit, {@link Service#EXHAUSTED}
     *         once per backoff period after the fast retries are spent,
     *         {@link Service#IDLE} otherwise
     */
    public Service service(long nowMs) {
        if (!active) {
            return Service.IDLE;
        }

        if (exhausted) {
            if (elapsed(nowMs, sentMs) < backoffMs) {
                return Service.IDLE;
            }
            sentMs = nowMs;
            return Service.EXHAUSTED;
        }

        if (retries >= maxRetries) {
            if (elapsed(nowMs, sentMs) < timeoutMs) {
                return Service.IDLE;
            }
            exhausted = true;
            sentMs = nowMs;
            return Service.EXHAUSTED;
        }

        if (elapsed(nowMs, sentMs) < timeoutMs) {
            return Service.IDLE;
        }

        retries++;
        sentMs = nowMs;
        return Service.SEND;
    }

    /**
     * Feeds an acknowledgement.
     *
     * @param ackSeq sequence number from the ACK frame
     * @return true when it acknowledged the outstanding command
     */
    public boolean onAck(int ackSeq) {
        if (!active || (ackSeq & 0xFF) != seq) {
            return false;
        }
        active = false;
        exhausted = false;
        return true;
    }

    /** Releases the window without a delivery (application abort). */
    public void abort() {
        active = false;
        exhausted = false;
    }

    /**
     * Payload to encode on {@link LLPProfileR1#CH_COMMAND}.
     *
     * @return the current {@code [SEQ][DATA]}, or an empty array when idle
     */
    public byte[] payload() {
        return active ? payload.clone() : new byte[0];
    }

    /**
     * Sequence number of the outstanding command.
     *
     * @return 0..255
     */
    public int seq() {
        return seq;
    }

    /**
     * Whether a command is awaiting its ACK.
     *
     * @return true while the window is busy
     */
    public boolean isActive() {
        return active;
    }

    /**
     * Fast retransmissions sent for the outstanding command.
     *
     * @return 0..maxRetries
     */
    public int retries() {
        return retries;
    }

    /**
     * Whether the fast retries are spent (waiting in backoff).
     *
     * @return true once {@link Service#EXHAUSTED} was reported
     */
    public boolean isExhausted() {
        return exhausted;
    }

    /** Wrap-safe 16-bit elapsed milliseconds. */
    private static int elapsed(long nowMs, long thenMs) {
        return (int) ((nowMs - thenMs) & 0xFFFF);
    }
}
