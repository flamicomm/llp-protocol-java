package com.flamingo.comm.llp.spec;

import com.flamingo.comm.llp.LLP;
import com.flamingo.comm.llp.LLPFrame;
import com.flamingo.comm.llp.LLPIncrementalParser;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Stream conformance: chunk boundaries must not change the outcome.
 *
 * <p>Exercises the chunk-oriented API ({@code feed(byte[])}), which is what a
 * transport adapter calls.</p>
 */
class StreamVectorTest {

    static List<TestCase> cases() throws IOException {
        return VectorLoader.loadByKind("llp-vectors", TestCase.Kind.STREAM);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void chunkBoundariesDoNotChangeTheOutcome(TestCase testCase) {
        LLPIncrementalParser receiver = LLP.incrementalParser()
                .capacity(testCase.capacity())
                .timeoutMs(testCase.timeoutMs())
                .build();

        for (byte[] chunk : testCase.chunks()) {
            receiver.feed(chunk, 0);
        }

        List<LLPFrame> frames = receiver.pollFrames();
        assertEquals(testCase.expectedFrames().size(), frames.size(),
                "[" + testCase.id() + "] delivered frame count");
        for (int i = 0; i < frames.size(); i++) {
            TestCase.ExpectedFrame want = testCase.expectedFrames().get(i);
            assertEquals(want.channel(), frames.get(i).channel(),
                    "[" + testCase.id() + "] frame " + i + " channel");
            assertArrayEquals(want.payload(), frames.get(i).payloadBytes(),
                    "[" + testCase.id() + "] frame " + i + " payload");
        }

        assertEquals(testCase.expectedErrors(), receiver.pollErrors(),
                "[" + testCase.id() + "] error list");
    }
}
