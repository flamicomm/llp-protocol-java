package com.flamingo.comm.llp;

import java.util.Optional;

/**
 * The four wire error codes of LLP 4.0.0.
 *
 * <p>The numeric values are part of the specification and are identical in every
 * implementation (the C enum uses {@code LLP4_ERR_CRC} = 1 … {@code LLP4_ERR_SYNC}
 * = 4). {@link #wireName()} returns the name used by the conformance vectors, so
 * a failure message can be matched against {@code llp-spec} verbatim.</p>
 *
 * <p>Errors are events, not fatal states: after reporting one the parser is
 * always ready for the next frame.</p>
 */
public enum LLPError {

    /** CRC mismatch on a complete frame. */
    CRC(1, "LLP_ERR_CRC", "CRC mismatch on a complete frame"),

    /** {@code LEN} is greater than the parser capacity. */
    LEN(2, "LLP_ERR_LEN", "LEN greater than the parser capacity"),

    /** The gap between two bytes exceeded {@code timeoutMs}. */
    TIMEOUT(3, "LLP_ERR_TIMEOUT", "inter-byte gap greater than timeoutMs"),

    /** Bad escape sequence, or a new magic inside a frame. */
    SYNC(4, "LLP_ERR_SYNC", "bad escape sequence, or a new magic inside a frame");

    private final int code;
    private final String wireName;
    private final String description;

    LLPError(int code, String wireName, String description) {
        this.code = code;
        this.wireName = wireName;
        this.description = description;
    }

    /**
     * Numeric code on the wire, identical to the C API ({@code LLP4_ERR_*}).
     *
     * @return 1..4
     */
    public int code() {
        return code;
    }

    /**
     * Name used by the conformance vectors, for example {@code LLP_ERR_CRC}.
     *
     * @return the specification-level name
     */
    public String wireName() {
        return wireName;
    }

    /**
     * Human-readable description.
     *
     * @return a short description
     */
    public String description() {
        return description;
    }

    /**
     * Maps a numeric wire code to the enum constant.
     *
     * @param code numeric code, 1..4
     * @return the matching error
     * @throws IllegalArgumentException if the code is not a v4 error code
     */
    public static LLPError fromCode(int code) {
        for (LLPError error : values()) {
            if (error.code == code) {
                return error;
            }
        }
        throw new IllegalArgumentException("Not an LLP 4.0.0 error code: " + code);
    }

    /**
     * Maps a numeric wire code to the enum constant without throwing.
     *
     * @param code numeric code
     * @return the matching error, or empty if the code is unknown
     */
    public static Optional<LLPError> maybeFromCode(int code) {
        for (LLPError error : values()) {
            if (error.code == code) {
                return Optional.of(error);
            }
        }
        return Optional.empty();
    }

    /**
     * Maps a vector name ({@code LLP_ERR_CRC}, …) to the enum constant.
     *
     * @param wireName the specification-level name
     * @return the matching error
     * @throws IllegalArgumentException if the name is not a v4 error name
     */
    public static LLPError fromWireName(String wireName) {
        for (LLPError error : values()) {
            if (error.wireName.equals(wireName)) {
                return error;
            }
        }
        throw new IllegalArgumentException("Not an LLP 4.0.0 error name: " + wireName);
    }
}
