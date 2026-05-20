package com.flamingo.llp.test;

import com.fazecast.jSerialComm.SerialPort;
import com.flamingo.comm.llp.core.*;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;

/**
 * Serial communication test with an Arduino running the LLP protocol v3.
 *
 * <p>Connects to /dev/ttyUSB0 at 115200 baud and sends LLP v3 transport frames.
 * The Arduino responds with text lines (OK/ERR/HB) rather than LLP frames.</p>
 *
 * <p>This test demonstrates the complete v3 API:</p>
 * <ul>
 *     <li>Building layer chains with {@link LLPFrameBuilder}</li>
 *     <li>Wrapping them into transport frames with {@link LLPTransportFramer}</li>
 *     <li>Parsing incoming bytes with {@link LLPIncrementalParser}</li>
 *     <li>Extracting payloads from {@link FinalNode}</li>
 * </ul>
 *
 * <p>Expected Arduino sketch: examples/serial_echo/serial_echo.ino from
 * the llp-protocol (C) repository.</p>
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
                .maxPayloadBytes(4096)
                .timeoutMs(1000)
                .build();

        // ================= TX LOOP =================
        Thread txThread = new Thread(() -> {
            // Build minimal LLP layer chain. The builder automatically inserts the FinalNode (0x00).
            LLPFrameBuilder<byte[]> builder = LLP.frameBuilder().build();
            int sequence = 1;

            System.out.println("--- Starting TX loop ---");

            try {
                while (!Thread.currentThread().isInterrupted()) {
                    String message = "Hello from Java #" + sequence;
                    byte[] appPayload = message.getBytes(StandardCharsets.UTF_8);

                    // 1. Build the layer chain: [0x00][payload]
                    byte[] layerChain = builder.build(ByteBuffer.wrap(appPayload));

                    // 2. Wrap into a transport frame: [0xAA][0x55][lenL][lenH][payload...][crcL][crcH]
                    //    with byte stuffing for every 0xAA byte.
                    byte[] transportFrame = LLPTransportFramer.buildSafe(layerChain);

                    out.write(transportFrame);
                    out.flush();

                    System.out.println("[TX] frame " + HexFormat.of().formatHex(transportFrame).toUpperCase()
                            + "  layerChainLen=" + layerChain.length
                            + "  transportLen=" + transportFrame.length
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
                    frame.chain().visit(visitor -> visitor
                            .on(FinalNode.class, node -> {
                                ByteBuffer payloadBuf = node.getPayload();
                                byte[] payload = new byte[payloadBuf.remaining()];
                                payloadBuf.get(payload);
                                String text = new String(payload, StandardCharsets.UTF_8);
                                System.out.println("[RX FRAME] payload=\"" + text + "\"  crc=" + frame.crc());
                            })
                    );
                }

                // ---- Poll transport errors ----
                List<TransportErrorCode> errors = parser.pollErrors();
                for (TransportErrorCode err : errors) {
                    System.err.println("[RX TRANSPORT ERROR] code=0x" + Integer.toHexString(err.code())
                            + " desc=" + err.description());
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
