package com.flamingo.comm.llp;

import java.util.Optional;

/**
 * Result of feeding one byte to {@link LLPParser}.
 *
 * <p>This is the Java counterpart of the C return value of
 * {@code llp4_parser_feed()} ({@code LLP4_EV_FRAME}, {@code LLP4_EV_NONE}, or
 * {@code -LLP4_ERR_*}). It is a sealed hierarchy so a {@code switch} over it is
 * exhaustive:</p>
 *
 * <pre>{@code
 * switch (parser.feed(b, now)) {
 *     case FeedResult.Frame f -> handle(parser.channel(), parser.payload());
 *     case FeedResult.Error e -> log(e.value());
 *     case FeedResult.None n  -> { }
 * }
 * }</pre>
 *
 * <p>{@link #none()} and {@link #frame()} are shared singletons, so the common
 * path (a byte that does not complete a frame) allocates nothing.</p>
 */
public sealed interface FeedResult {

    /** A complete, CRC-validated frame is available in the parser. */
    record Frame() implements FeedResult {
    }

    /**
     * The frame in progress was abandoned.
     *
     * @param value the reported error
     */
    record Error(LLPError value) implements FeedResult {
    }

    /** The byte was consumed and nothing observable happened. */
    record None() implements FeedResult {
    }

    /** Shared {@code NONE} instance. */
    FeedResult NONE = new None();

    /** Shared {@code FRAME} instance. */
    FeedResult FRAME = new Frame();

    /**
     * Returns the shared "nothing happened" result.
     *
     * @return the singleton
     */
    static FeedResult none() {
        return NONE;
    }

    /**
     * Returns the shared "frame delivered" result.
     *
     * @return the singleton
     */
    static FeedResult frame() {
        return FRAME;
    }

    /**
     * Builds an error result.
     *
     * @param error the reported error
     * @return a new result
     */
    static FeedResult error(LLPError error) {
        return new Error(error);
    }

    /**
     * Whether a frame was delivered.
     *
     * @return true for {@link Frame}
     */
    default boolean isFrame() {
        return this instanceof Frame;
    }

    /**
     * Whether an error was reported.
     *
     * @return true for {@link Error}
     */
    default boolean isError() {
        return this instanceof Error;
    }

    /**
     * The reported error, if any.
     *
     * @return the error, or empty for {@link Frame} and {@link None}
     */
    default Optional<LLPError> error() {
        return this instanceof Error e ? Optional.of(e.value()) : Optional.empty();
    }
}
