package com.flamingo.comm.llp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behaviour of {@link LLPProfileR1} (reliable command channel).
 *
 * <p>The traces T1–T9 come from llp-spec {@code docs/RELIABILITY.md} §11 and
 * mirror the C ({@code llp-protocol/test/test_v4_rel.c}) and Python
 * ({@code llp-spec/tools/selftest_reliability.py}) suites.</p>
 */
@DisplayName("Profile R1 — reliable command channel")
class LLPProfileR1Test {

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    @Nested
    @DisplayName("payload helpers")
    class Payloads {

        @Test
        void commandPayloadIsSequenceThenData() {
            assertArrayEquals(new byte[]{0x07, 'G', '1', '\n'},
                    LLPProfileR1.commandPayload(7, ascii("G1\n")));
        }

        @Test
        void sequenceWrapsAt256() {
            assertArrayEquals(new byte[]{(byte) 0xAA}, LLPProfileR1.ackPayload(0x1AA));
            assertEquals((byte) 0xAA, LLPProfileR1.commandPayload(0x2AA, new byte[0])[0]);
        }

        @Test
        void oversizedDataIsRejected() {
            assertThrows(IllegalArgumentException.class,
                    () -> LLPProfileR1.commandPayload(0, new byte[LLPProfileR1.MAX_DATA + 1]));
        }

        @Test
        void ackAndHelloPayloads() {
            assertArrayEquals(new byte[]{0x07}, LLPProfileR1.ackPayload(7));
            assertArrayEquals(new byte[]{(byte) 0x52}, LLPProfileR1.helloPayload());
            assertArrayEquals(new byte[]{0x2A}, LLPProfileR1.helloPayload(0x2A));
        }

        @Test
        void chunkingSplitsAndPreservesData() {
            byte[] data = new byte[200];
            for (int i = 0; i < data.length; i++) {
                data[i] = (byte) i;
            }
            var pieces = LLPProfileR1.chunks(data);

            assertEquals(4, pieces.size());
            assertEquals(63, pieces.get(0).length);
            assertEquals(11, pieces.get(3).length);

            byte[] joined = new byte[200];
            int offset = 0;
            for (byte[] piece : pieces) {
                System.arraycopy(piece, 0, joined, offset, piece.length);
                offset += piece.length;
            }
            assertArrayEquals(data, joined);
        }
    }

    @Nested
    @DisplayName("sender")
    class Sender {

        @Test
        void startsIdle() {
            LLPRelaySender sender = new LLPRelaySender();
            assertEquals(LLPRelaySender.Service.IDLE, sender.service(0));
            assertFalse(sender.isActive());
            assertEquals(0, sender.payload().length);
        }

        @Test
        void startBuildsThePayload() {
            LLPRelaySender sender = new LLPRelaySender();
            assertTrue(sender.start(7, ascii("G1 X0\n"), 100));
            assertArrayEquals(LLPProfileR1.commandPayload(7, ascii("G1 X0\n")), sender.payload());
            assertEquals(7, sender.seq());
        }

        @Test
        void busyWindowIsRejected() {
            LLPRelaySender sender = new LLPRelaySender();
            assertTrue(sender.start(1, ascii("a"), 0));
            assertFalse(sender.start(2, ascii("b"), 0));
            assertEquals(1, sender.seq());
        }

        @Test
        void oversizedDataIsRejected() {
            LLPRelaySender sender = new LLPRelaySender();
            assertFalse(sender.start(0, new byte[LLPProfileR1.MAX_DATA + 1], 0));
            assertFalse(sender.isActive());
        }

        @Test
        void retransmitsIdenticalPayloadAfterTheTimeout() {
            LLPRelaySender sender = new LLPRelaySender();
            sender.start(9, ascii("G1 X0\n"), 1000);
            byte[] first = sender.payload();

            assertEquals(LLPRelaySender.Service.IDLE, sender.service(1049));
            assertEquals(LLPRelaySender.Service.SEND, sender.service(1050));

            assertArrayEquals(first, sender.payload());
            assertEquals(1, sender.retries());
        }

        @Test
        void exhaustsAndBacksOff() {
            LLPRelaySender sender = new LLPRelaySender(50, 2, 1000);
            sender.start(1, ascii("x"), 0);

            assertEquals(LLPRelaySender.Service.SEND, sender.service(50));
            assertEquals(LLPRelaySender.Service.SEND, sender.service(100));
            assertEquals(LLPRelaySender.Service.EXHAUSTED, sender.service(150));
            assertEquals(LLPRelaySender.Service.IDLE, sender.service(1149));
            assertEquals(LLPRelaySender.Service.EXHAUSTED, sender.service(1150));
            assertTrue(sender.isActive());
            assertTrue(sender.isExhausted());
        }

        @Test
        void staleAckIsIgnored() {
            LLPRelaySender sender = new LLPRelaySender();
            sender.start(5, ascii("x"), 0);

            assertFalse(sender.onAck(4));
            assertFalse(sender.onAck(6));
            assertTrue(sender.isActive());

            assertTrue(sender.onAck(5));
            assertFalse(sender.isActive());
            assertFalse(sender.onAck(5), "an ACK is idempotent");
        }

        @Test
        void abortReleasesTheWindow() {
            LLPRelaySender sender = new LLPRelaySender();
            sender.start(7, ascii("x"), 0);
            sender.abort();
            assertFalse(sender.isActive());
        }

        @Test
        void timeoutArithmeticWrapsAt16Bits() {
            LLPRelaySender sender = new LLPRelaySender();
            sender.start(1, ascii("x"), 0xFFF0);

            assertEquals(LLPRelaySender.Service.IDLE, sender.service((0xFFF0 + 49) & 0xFFFF));
            assertEquals(LLPRelaySender.Service.SEND, sender.service((0xFFF0 + 50) & 0xFFFF));
        }
    }

    @Nested
    @DisplayName("receiver")
    class Receiver {

        @Test
        void firstFrameIsDeliveredAndDuplicatesSuppressed() {
            LLPRelayReceiver receiver = new LLPRelayReceiver();

            assertTrue(receiver.accept(3));
            assertFalse(receiver.accept(3));
            assertEquals(1, receiver.delivered());
            assertEquals(1, receiver.duplicates());
            assertEquals(3, receiver.lastSeq());
        }

        @Test
        void sequenceWrapsAt256() {
            LLPRelayReceiver receiver = new LLPRelayReceiver();

            assertTrue(receiver.accept(255));
            assertTrue(receiver.accept(0));
            assertTrue(receiver.accept(1));
            assertEquals(3, receiver.delivered());
        }

        @Test
        void sequenceJumpIsAccepted() {
            LLPRelayReceiver receiver = new LLPRelayReceiver();

            assertTrue(receiver.accept(10));
            assertTrue(receiver.accept(200));
            assertEquals(2, receiver.delivered());
        }
    }

    @Nested
    @DisplayName("conformance traces")
    class Traces {

        /** T1: send, accept, ACK. */
        @Test
        void t1CleanDelivery() {
            LLPRelaySender sender = new LLPRelaySender();
            LLPRelayReceiver receiver = new LLPRelayReceiver();

            assertTrue(sender.start(7, ascii("G1 X0\n"), 100));
            assertTrue(receiver.accept(7));
            assertTrue(sender.onAck(7));

            assertEquals(0, sender.retries());
            assertEquals(1, receiver.delivered());
        }

        /** T2: the command frame is lost; the retransmission delivers it once. */
        @Test
        void t2LostCommand() {
            LLPRelaySender sender = new LLPRelaySender();
            LLPRelayReceiver receiver = new LLPRelayReceiver();

            sender.start(11, ascii("G1 X1\n"), 0);
            assertEquals(LLPRelaySender.Service.SEND, sender.service(50));

            assertTrue(receiver.accept(11));
            assertTrue(sender.onAck(11));
            assertEquals(1, receiver.delivered());
            assertEquals(0, receiver.duplicates());
        }

        /** T3: the ACK is lost; the duplicate is discarded and ACKed again. */
        @Test
        void t3LostAck() {
            LLPRelaySender sender = new LLPRelaySender();
            LLPRelayReceiver receiver = new LLPRelayReceiver();

            sender.start(21, ascii("G1 X2\n"), 0);
            assertTrue(receiver.accept(21));

            assertEquals(LLPRelaySender.Service.SEND, sender.service(50));
            assertFalse(receiver.accept(21), "the retransmission must not run again");
            assertEquals(1, receiver.delivered());
            assertEquals(1, receiver.duplicates());

            assertTrue(sender.onAck(21));
            assertFalse(sender.isActive());
        }

        /** T4: a duplicate SEQ with different DATA is not delivered again. */
        @Test
        void t4DuplicateWithOtherData() {
            LLPRelayReceiver receiver = new LLPRelayReceiver();

            assertTrue(receiver.accept(33));
            assertFalse(receiver.accept(33));
            assertEquals(1, receiver.delivered());
        }

        /** T5: 255 -> 0 wrap, both delivered. */
        @Test
        void t5Wrap() {
            LLPRelaySender sender = new LLPRelaySender();
            LLPRelayReceiver receiver = new LLPRelayReceiver();

            sender.start(255, ascii("a"), 0);
            assertTrue(receiver.accept(255));
            assertTrue(sender.onAck(255));

            sender.start(0, ascii("b"), 0);
            assertTrue(receiver.accept(0));
            assertEquals(2, receiver.delivered());
        }

        /** T6: never acknowledged; the sender reports exhaustion. */
        @Test
        void t6Exhaustion() {
            LLPRelaySender sender = new LLPRelaySender(50, 3, 1000);
            sender.start(44, ascii("x"), 0);

            assertEquals(LLPRelaySender.Service.SEND, sender.service(50));
            assertEquals(LLPRelaySender.Service.SEND, sender.service(100));
            assertEquals(LLPRelaySender.Service.SEND, sender.service(150));
            assertEquals(LLPRelaySender.Service.EXHAUSTED, sender.service(200));
            assertTrue(sender.isActive(), "exhausted is not delivered");
        }

        /** T7: an ACK with an unknown SEQ is ignored. */
        @Test
        void t7UnknownAck() {
            LLPRelaySender sender = new LLPRelaySender();
            sender.start(5, ascii("x"), 0);

            assertFalse(sender.onAck(4));
            assertFalse(sender.onAck(6));
            assertTrue(sender.isActive());
        }

        /** T8: a duplicate arriving after the sender gave up is still ACKed. */
        @Test
        void t8LateDuplicateAfterExhaustion() {
            LLPRelaySender sender = new LLPRelaySender(50, 1, 1000);
            LLPRelayReceiver receiver = new LLPRelayReceiver();

            sender.start(60, ascii("x"), 0);
            assertTrue(receiver.accept(60));
            assertEquals(LLPRelaySender.Service.SEND, sender.service(50));
            assertFalse(receiver.accept(60));
            assertEquals(LLPRelaySender.Service.EXHAUSTED, sender.service(100));

            assertFalse(receiver.accept(60));
            assertFalse(receiver.accept(60));
            assertEquals(1, receiver.delivered());
            assertEquals(3, receiver.duplicates(), "every retransmission is suppressed");
        }

        /** T9: HELLO resets the session, so a restarted sender is not a duplicate. */
        @Test
        void t9HelloResetsTheSession() {
            LLPRelayReceiver receiver = new LLPRelayReceiver();

            assertTrue(receiver.accept(5));
            assertFalse(receiver.accept(5));
            assertEquals(1, receiver.duplicates());

            receiver.reset();
            assertTrue(receiver.accept(5), "new session: the same SEQ is delivered");
            assertEquals(1, receiver.delivered());
            assertEquals(0, receiver.duplicates());
        }
    }
}
