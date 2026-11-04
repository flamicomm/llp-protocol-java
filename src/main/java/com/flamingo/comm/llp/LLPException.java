package com.flamingo.comm.llp;

/**
 * Thrown when the library is used outside the limits of the wire format.
 *
 * <p>This is a programming-error exception: a payload larger than 255 bytes, a
 * channel outside {@code 0..255}, a capacity outside {@code 0..255}, or a
 * streaming encoder that is finished before every payload byte was written.
 * Wire-level failures are <b>not</b> exceptions: they are reported to the caller
 * as {@link LLPError} values by {@link LLPParser#feed(byte, int)}.</p>
 */
public class LLPException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates a new exception.
     *
     * @param message description of the misuse
     */
    public LLPException(String message) {
        super(message);
    }
}
