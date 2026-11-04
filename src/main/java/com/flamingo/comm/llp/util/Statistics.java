package com.flamingo.comm.llp.util;

/**
 * Counters for one parser instance.
 *
 * <p>Plain {@code long} fields on purpose: a parser belongs to a single link and
 * a single context (main loop or ISR, never both), so atomicity would only add
 * cost. Read them from the same thread that feeds the parser.</p>
 */
public final class Statistics {

    private long framesOk;
    private long framesError;
    private long timeouts;

    /** Records a delivered frame. */
    public void recordSuccess() {
        framesOk++;
    }

    /** Records a rejected frame. */
    public void recordError() {
        framesError++;
    }

    /** Records an inter-byte timeout. */
    public void recordTimeout() {
        timeouts++;
    }

    /**
     * Frames delivered since the last {@link #reset()}.
     *
     * @return the count
     */
    public long framesOk() {
        return framesOk;
    }

    /**
     * Frames rejected since the last {@link #reset()}.
     *
     * @return the count
     */
    public long framesError() {
        return framesError;
    }

    /**
     * Inter-byte timeouts since the last {@link #reset()}; always a subset of
     * {@link #framesError()}.
     *
     * @return the count
     */
    public long timeouts() {
        return timeouts;
    }

    /** Clears every counter. */
    public void reset() {
        framesOk = 0;
        framesError = 0;
        timeouts = 0;
    }
}
