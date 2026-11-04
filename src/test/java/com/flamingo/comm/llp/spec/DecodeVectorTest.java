package com.flamingo.comm.llp.spec;

import com.flamingo.comm.llp.FeedResult;
import com.flamingo.comm.llp.LLPError;
import com.flamingo.comm.llp.LLPParser;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decoder conformance: a byte stream fed one byte at a time must deliver exactly
 * the listed frames, in order, and report exactly the listed errors.
 *
 * <p>Time is simulated: {@code dt_ms} is added to a virtual clock before each
 * byte, so timeout cases run in microseconds and are deterministic.</p>
 */
class DecodeVectorTest {

    static List<TestCase> cases() throws IOException {
        return VectorLoader.loadByKind("llp-vectors", TestCase.Kind.DECODE);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void decodesTheExactEvents(TestCase testCase) {
        LLPParser parser = new LLPParser(testCase.capacity(), testCase.timeoutMs());

        List<TestCase.ExpectedFrame> frames = new ArrayList<>();
        List<LLPError> errors = new ArrayList<>();
        int now = 0;

        for (TestCase.Step step : testCase.steps()) {
            now += step.dtMs();
            FeedResult result = parser.feed(step.value(), now);
            if (result instanceof FeedResult.Frame) {
                frames.add(new TestCase.ExpectedFrame(parser.channel(), copy(parser.payload())));
            } else if (result instanceof FeedResult.Error error) {
                errors.add(error.value());
            }
        }

        assertEquals(testCase.expectedFrames().size(), frames.size(),
                "[" + testCase.id() + "] delivered frame count");
        for (int i = 0; i < frames.size(); i++) {
            TestCase.ExpectedFrame want = testCase.expectedFrames().get(i);
            TestCase.ExpectedFrame got = frames.get(i);
            assertEquals(want.channel(), got.channel(), "[" + testCase.id() + "] frame " + i + " channel");
            assertArrayEquals(want.payload(), got.payload(), "[" + testCase.id() + "] frame " + i + " payload");
        }

        assertEquals(testCase.expectedErrors(), errors, "[" + testCase.id() + "] error list");
        assertEquals(testCase.expectedTimeouts(), parser.statistics().timeouts(),
                "[" + testCase.id() + "] timeout counter");

        // Wycheproof model: valid means no error at all, invalid means at least one.
        if (testCase.result() == TestCase.Result.VALID) {
            assertTrue(errors.isEmpty(), "[" + testCase.id() + "] result is VALID but errors were reported");
        }
        if (testCase.result() == TestCase.Result.INVALID) {
            assertFalse(errors.isEmpty(), "[" + testCase.id() + "] result is INVALID but no error was reported");
        }
    }

    static byte[] copy(ByteBuffer buffer) {
        byte[] out = new byte[buffer.remaining()];
        buffer.duplicate().get(out);
        return out;
    }
}
