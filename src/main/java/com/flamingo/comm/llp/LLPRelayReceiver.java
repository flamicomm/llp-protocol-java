package com.flamingo.comm.llp;

/**
 * Receiver side of {@link LLPProfileR1}: duplicate suppression.
 *
 * <p>With a window of one, a single {@code lastSeq} is enough: a frame whose
 * sequence number matches the last accepted one is a retransmission (the ACK
 * was lost), so the caller must <strong>ACK it again and discard the data</strong>.
 * That is what makes retransmission safe for commands with side effects.</p>
 *
 * <p>The caller must verify that it can accept the whole {@code DATA}
 * <em>before</em> calling {@link #accept(int)}. If it cannot, it must not
 * consume the sequence number and must not send the ACK, so the sender retries
 * later (profile §6.1).</p>
 */
public final class LLPRelayReceiver {

    private int lastSeq;
    private boolean haveSeq;
    private int delivered;
    private int duplicates;

    /**
     * Classifies a command frame by its sequence number.
     *
     * @param seq sequence number from the command payload
     * @return true when {@code DATA} must be delivered to the application,
     *         false for a retransmission (ACK it again, discard the data)
     */
    public boolean accept(int seq) {
        int value = seq & 0xFF;
        if (haveSeq && value == lastSeq) {
            duplicates++;
            return false;
        }
        lastSeq = value;
        haveSeq = true;
        delivered++;
        return true;
    }

    /**
     * Starts a new session: forgets the last sequence number.
     *
     * <p>Call it when a {@link LLPProfileR1#CH_HELLO} frame arrives; a restarted
     * sender begins again at any sequence number and must not be mistaken for
     * a duplicate.</p>
     */
    public void reset() {
        lastSeq = 0;
        haveSeq = false;
        delivered = 0;
        duplicates = 0;
    }

    /**
     * Sequence number of the last accepted frame.
     *
     * @return 0..255 (meaningful only when {@link #hasSequence()})
     */
    public int lastSeq() {
        return lastSeq;
    }

    /**
     * Whether a frame was accepted since the last {@link #reset()}.
     *
     * @return true once a frame has been accepted
     */
    public boolean hasSequence() {
        return haveSeq;
    }

    /**
     * Frames delivered to the application.
     *
     * @return counter, incremented for new sequence numbers only
     */
    public int delivered() {
        return delivered;
    }

    /**
     * Retransmissions suppressed since the last {@link #reset()}.
     *
     * @return counter, incremented for duplicate sequence numbers
     */
    public int duplicates() {
        return duplicates;
    }
}
