package com.flamingo.comm.llp.spec;

import com.flamingo.comm.llp.LLPEncoder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * Encoder conformance: {@code (channel, payload)} must produce the exact frame
 * bytes of the specification.
 */
class EncodeVectorTest {

    static List<TestCase> cases() throws IOException {
        return VectorLoader.loadByKind("llp-vectors", TestCase.Kind.ENCODE);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void encodesTheExactFrame(TestCase testCase) {
        byte[] actual = LLPEncoder.encode(testCase.channel(), testCase.payload());
        assertArrayEquals(testCase.frame(), actual, "[" + testCase.id() + "] frame mismatch");
    }
}
