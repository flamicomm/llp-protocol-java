package com.flamingo.llp.test;

import com.fazecast.jSerialComm.SerialPort;
import com.flamingo.comm.llp.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * End-to-end demo of the {@link LLPProfileR1} reliable command channel.
 *
 * <p>Connects to /dev/ttyUSB0 at 115200 baud and talks to grbl-llp over a
 * direct USB/serial link. It does <strong>not</strong> work through the ESP
 * bridge: the ESP is the R1 <em>sender</em>, consumes the {@code CH_ACK} frames
 * itself and does not forward them to the PC (which talks to the ESP over
 * TCP). Unlike {@link SerialTest} and {@link SerialLLPShell}, which are
 * raw-wire tools on purpose, this class uses the profile end to end:</p>
 *
 * <ol>
 *     <li>Opens the port and drains the boot output: the CH340 resets the MCU
 *     when the port opens, and the firmware prints a banner for a few seconds.
 *     Those bytes are read and discarded (never fed to the parser).</li>
 *     <li>Sends {@link LLPProfileR1#CH_HELLO} twice, so losing one of them does
 *     not lose the session.</li>
 *     <li>Sends a short list of commands with stop-and-wait: one frame in
 *     flight, byte-identical retransmissions, {@code CH_ACK} on channel 2 and
 *     deduplication by {@code SEQ} on the device.</li>
 *     <li>Prints the channel-0 text ({@code ok}, {@code error:}, {@code [MSG...]})
 *     and a final summary with commands, ACKs, retransmissions and commands
 *     that were never acknowledged.</li>
 * </ol>
 *
 * <p>Expected firmware: grbl-llp (C) on wire 4.0.0 with profile R1 enabled.</p>
 */
public class SerialLLPTest {

    private static final String PORT_NAME = "/dev/ttyUSB0";
    private static final int BAUD_RATE = 115200;

    /** Semi-blocking read timeout while draining the boot banner. */
    private static final int READ_TIMEOUT_MS = 50;

    /**
     * Boot drain window. The CH340 pulls DTR when the port opens, resetting the
     * MCU; the firmware needs a few seconds before it starts listening (F-013,
     * F-014, F-018).
     */
    private static final int BOOT_DRAIN_MS = 4500;

    /**
     * Bounded drain after the last command. The device ACKs as soon as the line
     * is accepted into its RX ring, so {@code ok}/{@code error:} usually arrives
     * a little later and must be read before closing the port.
     */
    private static final int FINAL_DRAIN_MS = 400;

    /** Short command list: harmless queries plus one dwell. */
    private static final List<String> COMMANDS = List.of("$X", "$I", "G4 P0", "$G");

    public static void main(String[] args) throws Exception {

        // ================= SERIAL CONFIG =================
        SerialPort port = SerialPort.getCommPort(PORT_NAME);
        port.setBaudRate(BAUD_RATE);
        port.setNumDataBits(8);
        port.setNumStopBits(SerialPort.ONE_STOP_BIT);
        port.setParity(SerialPort.NO_PARITY);
        port.setComPortTimeouts(
                SerialPort.TIMEOUT_READ_SEMI_BLOCKING,
                READ_TIMEOUT_MS,
                0
        );

        if (!port.openPort()) {
            System.err.println("Cannot open serial port: " + PORT_NAME);
            return;
        }

        System.out.println("Connected to " + port.getSystemPortName());

        InputStream in = port.getInputStream();
        OutputStream out = port.getOutputStream();

        // Register shutdown hook for clean serial port closure on Ctrl+C
        final Thread mainThread = Thread.currentThread();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\nShutdown signal received. Closing port...");
            mainThread.interrupt();
            try {
                mainThread.join(2000);
            } catch (InterruptedException ignored) {
            }
            port.closePort();
        }));

        // ================= BOOT DRAIN =================
        // Read and drop the reset banner without feeding it to the parser: it
        // is plain text, not LLP, and would only produce sync errors.
        int discarded = drainBoot(in);
        System.out.println("[BOOT] discarded " + discarded + " byte(s) over "
                + BOOT_DRAIN_MS + " ms");

        // ================= PARSER (RX) =================
        LLPIncrementalParser parser = LLP.incrementalParser()
                .capacity(LLP.MAX_CAPACITY)
                .timeoutMs(LLP.DEFAULT_TIMEOUT_MS)
                .build();

        // ================= HELLO =================
        // Sent twice by project convention: the device resets its dedup state
        // on every HELLO, so losing one of the two is harmless.
        byte[] hello = LLP.encode(LLPProfileR1.CH_HELLO, LLPProfileR1.helloPayload());
        for (int i = 0; i < 2; i++) {
            out.write(hello);
            out.flush();
        }
        System.out.println("[HELLO] sent twice, session=0x"
                + String.format("%02X", LLPProfileR1.DEFAULT_SESSION));

        // ================= R1 COMMAND LOOP =================
        // One sender per session: the window is reused by every command. The
        // sender's fast retries (5 x 50 ms by default) bound each wait, so no
        // extra wall-clock guard is needed: EXHAUSTED is the failure signal.
        LLPRelaySender sender = new LLPRelaySender();
        int seq = 0;
        int attempted = 0;
        int skipped = 0;
        int acked = 0;
        int retries = 0;
        int notDelivered = 0;
        boolean linkClosed = false;

        byte[] readBuffer = new byte[256];

        for (String command : COMMANDS) {
            if (Thread.currentThread().isInterrupted() || linkClosed) {
                break;
            }

            byte[] data = command.getBytes(StandardCharsets.UTF_8);
            if (!sender.start(seq, data, nowMs())) {
                // The window should be free here; report it instead of lying
                // in the summary.
                skipped++;
                System.err.println("[SKIP] window busy or payload too long: \"" + command + "\"");
                continue;
            }
            attempted++;

            byte[] frame = LLP.encode(LLPProfileR1.CH_COMMAND, sender.payload());
            out.write(frame);
            out.flush();
            System.out.println("[CMD] seq=" + seq + " \"" + command + "\"");

            boolean closed = false;
            boolean exhausted = false;

            while (!closed && !exhausted
                    && !Thread.currentThread().isInterrupted() && !linkClosed) {
                int n = in.read(readBuffer);
                if (n < 0) {
                    // jSerialComm: -1 means the port was closed / end of stream,
                    // not a read timeout (a timeout returns 0).
                    System.err.println("[LINK] port closed or end of stream at seq=" + seq);
                    linkClosed = true;
                    break;
                }

                // Timestamp after the (possibly blocking) read so service()
                // sees the real elapsed time.
                long now = nowMs();

                if (n > 0) {
                    for (int i = 0; i < n; i++) {
                        parser.feed(readBuffer[i], (int) now);
                    }
                    for (LLPFrame received : parser.pollFrames()) {
                        byte[] payload = received.payloadBytes();
                        if (received.channel() == LLPProfileR1.CH_ACK) {
                            if (payload.length == 1) {
                                int ackSeq = payload[0] & 0xFF;
                                if (sender.onAck(ackSeq)) {
                                    // Only an ACK that actually closed this
                                    // window counts as delivered.
                                    closed = true;
                                    acked++;
                                    System.out.println("[ACK] seq=" + ackSeq);
                                }
                            } else {
                                System.err.println("[RX] ignored malformed ACK, len="
                                        + payload.length);
                            }
                        } else if (received.channel() == 0) {
                            System.out.println("[DATA] " + new String(payload, StandardCharsets.UTF_8).trim());
                        } else {
                            System.out.println("[RX] channel=" + received.channel()
                                    + " len=" + payload.length);
                        }
                    }
                    for (LLPError err : parser.pollErrors()) {
                        System.err.println("[RX LLP ERROR] " + err.wireName()
                                + " code=" + err.code() + " desc=" + err.description());
                    }
                }

                if (!closed) {
                    switch (sender.service(now)) {
                        case SEND -> {
                            // Byte-identical retransmission: the device dedups by SEQ.
                            out.write(LLP.encode(LLPProfileR1.CH_COMMAND, sender.payload()));
                            out.flush();
                            System.out.println("[RETRY] seq=" + seq + " attempt=" + sender.retries());
                        }
                        case EXHAUSTED -> {
                            exhausted = true;
                            System.out.println("[EXHAUSTED] seq=" + seq + " not acknowledged");
                        }
                        case IDLE -> {
                            // Still waiting inside the ACK timeout.
                        }
                    }
                }
            }

            retries += sender.retries();

            // Burn the sequence number whether or not the command was delivered
            // (RELIABILITY §4). Reusing it with different DATA would make the
            // device silently dedup the next command, and a late ACK could
            // close the wrong window. Same discipline as llp_link.py/test_r1.py.
            seq = (seq + 1) & 0xFF;

            if (!closed) {
                notDelivered++;
                sender.abort();
            }
        }

        // Bounded drain so the last command's ok/error is printed before closing.
        if (!linkClosed && !Thread.currentThread().isInterrupted()) {
            drainChannelZero(in, parser, FINAL_DRAIN_MS);
        }

        // ================= SUMMARY =================
        System.out.println("[SUMMARY] commands=" + COMMANDS.size()
                + " attempted=" + attempted
                + " acked=" + acked
                + " retries=" + retries
                + " not-delivered=" + notDelivered
                + " skipped=" + skipped);

        // Cleanup
        port.closePort();
        System.out.println("Port closed.");
    }

    /**
     * Reads and discards everything the device prints while it boots.
     *
     * @param in port input stream with a semi-blocking read timeout
     * @return number of discarded bytes
     * @throws IOException if the port read fails
     */
    private static int drainBoot(InputStream in) throws IOException {
        byte[] buffer = new byte[256];
        long deadline = System.currentTimeMillis() + BOOT_DRAIN_MS;
        int discarded = 0;
        while (System.currentTimeMillis() < deadline
                && !Thread.currentThread().isInterrupted()) {
            int n = in.read(buffer);
            if (n < 0) {
                // Port closed / EOF: nothing left to drain.
                return discarded;
            }
            if (n > 0) {
                discarded += n;
            }
        }
        return discarded;
    }

    /**
     * Drains channel-0 text for a bounded window after the last command.
     *
     * <p>The ACK is a transport event and arrives before grbl executes the
     * line, so {@code ok}/{@code error:} must be read separately. The drain
     * also prints any remaining {@link LLPError}.</p>
     *
     * @param in       port input stream with a semi-blocking read timeout
     * @param parser   parser fed with the drained bytes
     * @param windowMs maximum time to keep draining
     * @throws IOException if the port read fails
     */
    private static void drainChannelZero(InputStream in, LLPIncrementalParser parser, int windowMs)
            throws IOException {
        byte[] buffer = new byte[256];
        long deadline = System.currentTimeMillis() + windowMs;
        while (System.currentTimeMillis() < deadline
                && !Thread.currentThread().isInterrupted()) {
            int n = in.read(buffer);
            if (n < 0) {
                return;
            }
            if (n == 0) {
                continue;
            }

            long now = nowMs();
            for (int i = 0; i < n; i++) {
                parser.feed(buffer[i], (int) now);
            }
            for (LLPFrame received : parser.pollFrames()) {
                if (received.channel() == 0) {
                    String text = new String(received.payloadBytes(),
                            StandardCharsets.UTF_8).trim();
                    if (!text.isEmpty()) {
                        System.out.println("[DATA] " + text);
                    }
                }
            }
            for (LLPError err : parser.pollErrors()) {
                System.err.println("[RX LLP ERROR] " + err.wireName()
                        + " code=" + err.code() + " desc=" + err.description());
            }
        }
    }

    /** Monotonic milliseconds for the injected R1 clock. */
    private static long nowMs() {
        return System.nanoTime() / 1_000_000L;
    }
}
