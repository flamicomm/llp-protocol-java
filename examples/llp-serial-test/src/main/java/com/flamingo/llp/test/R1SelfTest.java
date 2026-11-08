package com.flamingo.llp.test;

import com.flamingo.comm.llp.*;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Hardware-free integration test for the {@link LLPProfileR1} command channel.
 *
 * <p>It simulates both ends in memory: a host built around
 * {@link LLPRelaySender} writes LLP frames, and a {@link FakeDevice} parses
 * them with {@link LLPIncrementalParser}, deduplicates with
 * {@link LLPRelayReceiver} and answers with {@code CH_ACK} frames. Time is a
 * plain millisecond counter injected into both state machines, so the run is
 * deterministic and never sleeps.</p>
 *
 * <p>The five scenarios cover the profile guarantees that matter for safety:
 * clean delivery, a lost ACK (retransmission + dedup), a lost command,
 * {@code HELLO} resetting the dedup state and the 255→0 sequence wrap. Each
 * check asserts an exact counter, so breaking the sender or the receiver makes
 * the self-test fail.</p>
 *
 * <p>Usage: {@code java com.flamingo.llp.test.R1SelfTest}; it prints
 * {@code [PASS]}/{@code [FAIL]} lines and exits 0 or 1.</p>
 */
public final class R1SelfTest {

    /** Virtual clock step between polls. */
    private static final int STEP_MS = 10;

    /** Upper bound so a broken state machine cannot loop forever. */
    private static final int MAX_STEPS = 10_000;

    private static int checks;
    private static int failures;

    public static void main(String[] args) {
        System.out.println("LLP profile R1 self-test (no hardware, injected clock)");
        System.out.println();

        scenarioCleanDelivery();
        scenarioLostAck();
        scenarioLostCommand();
        scenarioHelloResetsDedup();
        scenarioSequenceWrap();

        System.out.println();
        System.out.println("Checks: " + (checks - failures) + "/" + checks + " passed");
        if (failures > 0) {
            System.out.println("RESULT: FAIL");
            System.exit(1);
        }
        System.out.println("RESULT: PASS");
    }

    // ------------------------------------------------------------------
    // Scenarios
    // ------------------------------------------------------------------

    /** Five commands in a row: five ACKs, five deliveries, no retries. */
    private static void scenarioCleanDelivery() {
        System.out.println("-- scenario 1: clean delivery --");
        Sim sim = new Sim();

        boolean allAcked = true;
        int retries = 0;
        for (int i = 0; i < 5; i++) {
            allAcked &= sim.sendCommand(i, "G4 P0 ; line " + i);
            retries += sim.lastRetries();
        }

        check("5 commands acknowledged", allAcked);
        check("5 ACK frames received (got " + sim.acks() + ")", sim.acks() == 5);
        check("5 deliveries (got " + sim.device().deliveredCount() + ")",
                sim.device().deliveredCount() == 5);
        check("0 retransmissions (got " + retries + ")", retries == 0);
        check("0 duplicates (got " + sim.device().duplicates() + ")",
                sim.device().duplicates() == 0);
    }

    /** The first ACK is lost: one retransmission, one delivery, one final ACK. */
    private static void scenarioLostAck() {
        System.out.println("-- scenario 2: lost ACK --");
        Sim sim = new Sim();
        sim.device().dropNextAcks(1);

        boolean acked = sim.sendCommand(0, "$X");

        check("command acknowledged after the retransmission", acked);
        check("1 retransmission (got " + sim.lastRetries() + ")", sim.lastRetries() == 1);
        check("delivered exactly once (got " + sim.device().deliveredCount() + ")",
                sim.device().deliveredCount() == 1);
        check("duplicate detected and suppressed (got " + sim.device().duplicates() + ")",
                sim.device().duplicates() == 1);
        check("device emitted the final ACK (got " + sim.device().acksSent() + ")",
                sim.device().acksSent() == 1);
        check("host received 1 ACK (got " + sim.acks() + ")", sim.acks() == 1);
        check("retransmission is byte-identical", retransmissionIsIdentical(sim));
    }

    /** The first command frame is lost: retransmission, one delivery. */
    private static void scenarioLostCommand() {
        System.out.println("-- scenario 3: lost command --");
        Sim sim = new Sim();
        sim.device().dropNextCommands(1);

        boolean acked = sim.sendCommand(0, "$I");

        check("command delivered after the retransmission", acked);
        check("1 retransmission (got " + sim.lastRetries() + ")", sim.lastRetries() == 1);
        check("delivered exactly once (got " + sim.device().deliveredCount() + ")",
                sim.device().deliveredCount() == 1);
        check("0 duplicates (got " + sim.device().duplicates() + ")",
                sim.device().duplicates() == 0);
        check("retransmission is byte-identical", retransmissionIsIdentical(sim));
    }

    /** HELLO clears the last SEQ, so reusing 7 after it is a new command. */
    private static void scenarioHelloResetsDedup() {
        System.out.println("-- scenario 4: HELLO resets dedup --");
        Sim sim = new Sim();

        boolean first = sim.sendCommand(7, "G91");
        sim.sendHello();
        boolean second = sim.sendCommand(7, "G90");

        check("first SEQ 7 acknowledged", first);
        check("second SEQ 7 acknowledged", second);
        check("2 deliveries despite the repeated SEQ (got "
                        + sim.device().deliveredCount() + ")",
                sim.device().deliveredCount() == 2);
        check("0 duplicates (got " + sim.device().duplicates() + ")",
                sim.device().duplicates() == 0);
        check("1 HELLO processed (got " + sim.device().hellos() + ")",
                sim.device().hellos() == 1);
        check("commands delivered in order",
                sim.device().delivered().equals(List.of("G91", "G90")));
    }

    /** 255 is followed by 0: the wrap must not be mistaken for a duplicate. */
    private static void scenarioSequenceWrap() {
        System.out.println("-- scenario 5: sequence wrap 255 -> 0 --");
        Sim sim = new Sim();

        boolean last = sim.sendCommand(255, "M5");
        boolean first = sim.sendCommand(0, "M3");

        check("SEQ 255 acknowledged", last);
        check("SEQ 0 acknowledged after the wrap", first);
        check("both delivered (got " + sim.device().deliveredCount() + ")",
                sim.device().deliveredCount() == 2);
        check("device last SEQ is 0 (got " + sim.device().lastSeq() + ")",
                sim.device().lastSeq() == 0);
        check("commands delivered in order",
                sim.device().delivered().equals(List.of("M5", "M3")));
    }

    // ------------------------------------------------------------------
    // Assertions
    // ------------------------------------------------------------------

    private static void check(String label, boolean ok) {
        checks++;
        if (!ok) {
            failures++;
        }
        System.out.println((ok ? "[PASS] " : "[FAIL] ") + label);
    }

    private static boolean retransmissionIsIdentical(Sim sim) {
        List<byte[]> frames = sim.commandFrames();
        return frames.size() == 2 && Arrays.equals(frames.get(0), frames.get(1));
    }

    // ------------------------------------------------------------------
    // Host side
    // ------------------------------------------------------------------

    /**
     * Host side of the link: one {@link LLPRelaySender} driven by a virtual
     * clock, with its own parser for the device responses.
     */
    private static final class Sim {

        private final FakeDevice device = new FakeDevice();
        private final LLPIncrementalParser replies = LLP.incrementalParser()
                .capacity(LLP.MAX_CAPACITY)
                .timeoutMs(LLP.DEFAULT_TIMEOUT_MS)
                .build();
        private final LLPRelaySender sender = new LLPRelaySender();
        private final List<byte[]> commandFrames = new ArrayList<>();

        private long nowMs;
        private int acks;
        private int lastRetries;

        /**
         * Runs one command until its ACK closes the window or the fast
         * retries are spent.
         *
         * @param seq  sequence number
         * @param line command text
         * @return true when the command was acknowledged
         */
        boolean sendCommand(int seq, String line) {
            commandFrames.clear();
            if (!sender.start(seq, line.getBytes(StandardCharsets.UTF_8), nowMs)) {
                throw new IllegalStateException("window busy before SEQ " + seq);
            }
            transmit();

            for (int step = 0; step < MAX_STEPS; step++) {
                pump();
                if (!sender.isActive()) {
                    lastRetries = sender.retries();
                    return true;
                }

                nowMs += STEP_MS;
                switch (sender.service(nowMs)) {
                    case SEND -> transmit();
                    case EXHAUSTED -> {
                        lastRetries = sender.retries();
                        return false;
                    }
                    case IDLE -> {
                        // Still inside the ACK timeout.
                    }
                }
            }
            throw new IllegalStateException("simulation did not converge for SEQ " + seq);
        }

        /** Sends one HELLO frame (fire and forget: the device does not ACK it). */
        void sendHello() {
            device.receive(LLP.encode(LLPProfileR1.CH_HELLO,
                    LLPProfileR1.helloPayload()), (int) nowMs);
        }

        private void transmit() {
            byte[] frame = LLP.encode(LLPProfileR1.CH_COMMAND, sender.payload());
            commandFrames.add(frame.clone());
            device.receive(frame, (int) nowMs);
        }

        /** Drains device responses and feeds the host parser. */
        private void pump() {
            byte[] fromDevice = device.drainResponses();
            if (fromDevice.length == 0) {
                return;
            }
            replies.feed(fromDevice, (int) nowMs);
            for (LLPFrame frame : replies.pollFrames()) {
                byte[] payload = frame.payloadBytes();
                if (frame.channel() == LLPProfileR1.CH_ACK && payload.length >= 1) {
                    acks++;
                    sender.onAck(payload[0] & 0xFF);
                }
            }
            replies.pollErrors();
        }

        int acks() {
            return acks;
        }

        int lastRetries() {
            return lastRetries;
        }

        List<byte[]> commandFrames() {
            return commandFrames;
        }

        FakeDevice device() {
            return device;
        }
    }

    // ------------------------------------------------------------------
    // Device side
    // ------------------------------------------------------------------

    /**
     * Device side of the link: parser + {@link LLPRelayReceiver} + ACK writer.
     *
     * <p>Drop hooks simulate the two losses that matter: a command frame that
     * never arrives (no delivery, no ACK) and an ACK that never reaches the
     * host (the command was delivered, but the sender must retransmit).</p>
     */
    private static final class FakeDevice {

        private final LLPIncrementalParser parser = LLP.incrementalParser()
                .capacity(LLP.MAX_CAPACITY)
                .timeoutMs(LLP.DEFAULT_TIMEOUT_MS)
                .build();
        private final LLPRelayReceiver receiver = new LLPRelayReceiver();
        private final ByteArrayOutputStream responses = new ByteArrayOutputStream();
        private final List<String> delivered = new ArrayList<>();

        private int hellos;
        private int acksSent;
        private int dropCommands;
        private int dropAcks;

        /** Drops the next {@code count} command frames (no delivery, no ACK). */
        void dropNextCommands(int count) {
            dropCommands = count;
        }

        /** Drops the next {@code count} ACK frames (the command is delivered). */
        void dropNextAcks(int count) {
            dropAcks = count;
        }

        /** Feeds bytes written by the host. */
        void receive(byte[] bytes, int nowMs) {
            parser.feed(bytes, nowMs);
            for (LLPFrame frame : parser.pollFrames()) {
                handle(frame);
            }
            parser.pollErrors();
        }

        private void handle(LLPFrame frame) {
            switch (frame.channel()) {
                case LLPProfileR1.CH_HELLO -> {
                    // A new session: forget the last SEQ so a restarted sender
                    // is never mistaken for a duplicate.
                    receiver.reset();
                    hellos++;
                }
                case LLPProfileR1.CH_COMMAND -> handleCommand(frame.payloadBytes());
                default -> {
                    // ACK/other channels are not sent to the device in this sim.
                }
            }
        }

        private void handleCommand(byte[] payload) {
            if (payload.length == 0) {
                return;
            }
            if (dropCommands > 0) {
                dropCommands--;
                return;
            }

            int seq = payload[0] & 0xFF;
            if (receiver.accept(seq)) {
                delivered.add(new String(payload, 1, payload.length - 1,
                        StandardCharsets.UTF_8));
            }

            if (dropAcks > 0) {
                dropAcks--;
                return;
            }
            responses.writeBytes(LLP.encode(LLPProfileR1.CH_ACK,
                    LLPProfileR1.ackPayload(seq)));
            acksSent++;
        }

        byte[] drainResponses() {
            byte[] out = responses.toByteArray();
            responses.reset();
            return out;
        }

        int deliveredCount() {
            return delivered.size();
        }

        List<String> delivered() {
            return delivered;
        }

        int duplicates() {
            return receiver.duplicates();
        }

        int lastSeq() {
            return receiver.lastSeq();
        }

        int hellos() {
            return hellos;
        }

        int acksSent() {
            return acksSent;
        }
    }
}
