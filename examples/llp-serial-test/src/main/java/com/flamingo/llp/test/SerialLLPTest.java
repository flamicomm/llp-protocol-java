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
 * <p>Connects to /dev/ttyUSB0 at 115200 baud and talks to a device that
 * implements the R1 profile (grbl-llp or the ESP bridge). Unlike
 * {@link SerialTest} and {@link SerialLLPShell}, which are raw-wire tools on
 * purpose, this class uses the profile end to end:</p>
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

    /** Safety net so a wedged link cannot hang the demo forever. */
    private static final int COMMAND_TIMEOUT_MS = 5000;

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
        // One sender per session: the window is reused by every command.
        LLPRelaySender sender = new LLPRelaySender();
        int seq = 0;
        int acked = 0;
        int retries = 0;
        int notDelivered = 0;

        byte[] readBuffer = new byte[256];

        for (String command : COMMANDS) {
            if (Thread.currentThread().isInterrupted()) {
                break;
            }

            byte[] data = command.getBytes(StandardCharsets.UTF_8);
            if (!sender.start(seq, data, nowMs())) {
                System.err.println("[SKIP] window busy or payload too long: \"" + command + "\"");
                continue;
            }

            byte[] frame = LLP.encode(LLPProfileR1.CH_COMMAND, sender.payload());
            out.write(frame);
            out.flush();
            System.out.println("[CMD] seq=" + seq + " \"" + command + "\"");

            boolean closed = false;
            boolean exhausted = false;
            long started = System.currentTimeMillis();

            while (!closed && !exhausted && !Thread.currentThread().isInterrupted()) {
                long now = nowMs();

                int n = in.read(readBuffer);
                if (n > 0) {
                    for (int i = 0; i < n; i++) {
                        parser.feed(readBuffer[i], (int) now);
                    }
                    for (LLPFrame received : parser.pollFrames()) {
                        byte[] payload = received.payloadBytes();
                        if (received.channel() == LLPProfileR1.CH_ACK && payload.length >= 1) {
                            int ackSeq = payload[0] & 0xFF;
                            if (sender.onAck(ackSeq)) {
                                closed = true;
                                acked++;
                                System.out.println("[ACK] seq=" + ackSeq);
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

                if (!closed && !exhausted
                        && System.currentTimeMillis() - started > COMMAND_TIMEOUT_MS) {
                    System.err.println("[TIMEOUT] command guard expired at seq=" + seq);
                    sender.abort();
                    exhausted = true;
                }
            }

            retries += sender.retries();
            if (closed) {
                seq = (seq + 1) & 0xFF;
            } else {
                notDelivered++;
                sender.abort();
            }
        }

        // ================= SUMMARY =================
        System.out.println("[SUMMARY] commands=" + COMMANDS.size()
                + " acked=" + acked
                + " retries=" + retries
                + " not-delivered=" + notDelivered);

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
            if (n > 0) {
                discarded += n;
            }
        }
        return discarded;
    }

    /** Monotonic milliseconds for the injected R1 clock. */
    private static long nowMs() {
        return System.nanoTime() / 1_000_000L;
    }
}
