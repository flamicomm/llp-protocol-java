package com.flamingo.llp.test;

import com.fazecast.jSerialComm.SerialPort;
import com.flamingo.comm.llp.*;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;

/**
 * Serial communication test against a device running LLP wire format 4.0.0.
 *
 * <p>Connects to /dev/ttyUSB0 at 115200 baud and sends v4 frames. The device may
 * answer with LLP frames or with plain text lines (OK/ERR/HB).</p>
 *
 * <p>Demonstrates the complete v4 API:</p>
 * <ul>
 *     <li>Encoding frames with {@link LLP#encode(int, byte[])} (channel + payload)</li>
 *     <li>Parsing incoming bytes with {@link LLPIncrementalParser}</li>
 *     <li>Reading {@link LLPFrame#channel()} and {@link LLPFrame#payloadBytes()}</li>
 *     <li>Mapping {@link LLPError} to the four wire error codes</li>
 * </ul>
 *
 * <p>Expected firmware: grbl-llp (C) or the ESP bridge, both on wire 4.0.0.</p>
 */
public class SerialLLPTest {

    private static final String PORT_NAME = "/dev/ttyUSB0";
    private static final int BAUD_RATE = 115200;
    private static final int RESET_DELAY_MS = 2000;
    private static final int TX_INTERVAL_MS = 3000;

    public static void main(String[] args) throws Exception {

        // ================= SERIAL CONFIG =================
        SerialPort port = SerialPort.getCommPort(PORT_NAME);
        port.setBaudRate(BAUD_RATE);
        port.setNumDataBits(8);
        port.setNumStopBits(SerialPort.ONE_STOP_BIT);
        port.setParity(SerialPort.NO_PARITY);
        port.setComPortTimeouts(
                SerialPort.TIMEOUT_READ_BLOCKING,
                0,
                0
        );

        if (!port.openPort()) {
            System.err.println("Cannot open serial port: " + PORT_NAME);
            return;
        }

        System.out.println("Connected to " + port.getSystemPortName());
        System.out.println("Waiting for Arduino reset...");
        Thread.sleep(RESET_DELAY_MS);

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

        // ================= PARSER (RX) =================
        LLPIncrementalParser parser = LLP.incrementalParser()
                .capacity(LLP.MAX_CAPACITY)
                .timeoutMs(1000)
                .build();

        // ================= TX LOOP =================
        Thread txThread = new Thread(() -> {
            int sequence = 1;

            System.out.println("--- Starting TX loop ---");

            try {
                while (!Thread.currentThread().isInterrupted()) {
                    String message = "Hello from Java #" + sequence;
                    byte[] appPayload = message.getBytes(StandardCharsets.UTF_8);

                    // Encode one v4 frame: AA 55 | LEN | CHANNEL | payload | CRC16
                    byte[] frame = LLP.encode(0, appPayload);

                    out.write(frame);
                    out.flush();

                    System.out.println("[TX] channel=0 frame=" + HexFormat.of().formatHex(frame).toUpperCase()
                            + "  payloadLen=" + appPayload.length
                            + "  frameLen=" + frame.length
                            + "  msg=\"" + message + "\"");

                    sequence++;
                    Thread.sleep(TX_INTERVAL_MS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception ex) {
                System.err.println("[TX ERROR] " + ex.getMessage());
                ex.printStackTrace();
            }
        });

        txThread.setDaemon(true);
        txThread.start();

        // ================= RX THREAD =================
        StringBuilder asciiBuffer = new StringBuilder();

        while (!Thread.currentThread().isInterrupted()) {
            try {
                int data = in.read();

                if (data < 0) {
                    // End of stream
                    break;
                }

                byte b = (byte) data;
                parser.feed(b);

                // ---- Poll parsed LLP frames ----
                List<LLPFrame> frames = parser.pollFrames();
                for (LLPFrame frame : frames) {
                    byte[] payload = frame.payloadBytes();
                    String text = new String(payload, StandardCharsets.UTF_8);
                    System.out.println("[RX FRAME] channel=" + frame.channel()
                            + " payload=\"" + text + "\""
                            + " hex=" + HexFormat.of().formatHex(payload).toUpperCase()
                            + " crc=0x" + Integer.toHexString(frame.crc()));
                }

                // ---- Poll errors ----
                List<LLPError> errors = parser.pollErrors();
                for (LLPError err : errors) {
                    System.err.println("[RX LLP ERROR] " + err.wireName()
                            + " code=" + err.code() + " desc=" + err.description());
                }

                // ---- Print raw ASCII responses from Arduino (OK, ERR, HB) ----
                if (b == '\n') {
                    String line = asciiBuffer.toString();
                    if (!line.isEmpty()) {
                        System.out.println("[RX ASCII] " + line);
                    }
                    asciiBuffer.setLength(0);
                } else if (b >= 0x20 && b < 0x7F) {
                    // Only buffer printable ASCII to avoid garbling from binary LLP bytes
                    asciiBuffer.append((char) b);
                } else if (b == '\r') {
                    // ignore carriage return
                } else {
                    // Non-printable byte (part of LLP frame or binary data) —
                    // discard from ASCII buffer to keep output clean
                    asciiBuffer.setLength(0);
                }

            } catch (Exception e) {
                System.err.println("[WARN] Read error: " + e.getMessage());
            }
        }

        // Cleanup
        txThread.interrupt();
        port.closePort();
        System.out.println("Port closed.");
    }
}
