package com.flamingo.llp.test;

import com.fazecast.jSerialComm.SerialPort;
import com.flamingo.comm.llp.core.*;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/**
 * Serial communication test with an Arduino running the LLP protocol.
 *
 * <p>Connects to /dev/ttyUSB0 at 115200 baud and sends LLP v3 frames
 * containing simple text payloads. The Arduino responds with text lines
 * (OK/ERR/HB) rather than LLP frames.</p>
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
        SerialPort port = SerialPort.getCommPort("/dev/ttyUSB0");
        port.setBaudRate(115200);
        port.setNumDataBits(8);
        port.setNumStopBits(SerialPort.ONE_STOP_BIT);
        port.setParity(SerialPort.NO_PARITY);
        port.setComPortTimeouts(
                SerialPort.TIMEOUT_READ_BLOCKING,
                0,
                0
        );

        if (!port.openPort()) {
            System.err.println("❌ Cannot open serial port");
            return;
        }

        System.out.println("✅ Connected to " + port.getSystemPortName());
        System.out.println("⏳ Waiting for Arduino reset...");
        Thread.sleep(2000);

        InputStream in = port.getInputStream();
        OutputStream out = port.getOutputStream();

        LLPTransportDeframer deframer = new LLPTransportDeframer(4096, 100);
        deframer.addListener(new LLPTransportDeframer.LLPFrameListener() {
            @Override
            public void onFrameReceived(LLPRawFrame frame) {
                System.out.println("[RX FRAME] " + new String(frame.payload().array()));
            }

            @Override
            public void onFrameError(TransportErrorCode transportErrorCode) {
                System.err.println("[RX FRAME ERROR] code:" + transportErrorCode.code() + " desc:" + transportErrorCode.description());
            }
        });

        // ================= TX LOOP =================
        Thread txThread = new Thread(() -> {
            // Build minimal LLP frames. The builder automatically inserts the FinalNode (0x00).
            LLPFrameBuilder<byte[]> builder = LLP.frameBuilder().build();
            int sequence = 1;

            System.out.println("--- Starting TX loop ---");

            try {
                while (true) {
                    String message = "Hello from Java #" + sequence;
                    byte[] payload = message.getBytes(StandardCharsets.UTF_8);

                    // Pass the raw application payload directly.
                    // The builder will add the 0x00 marker before it.
                    byte[] frame = builder.build(ByteBuffer.wrap(payload));

                    out.write(frame);
                    out.flush();

                    System.out.println("[TX] frame " + HexFormat.of().formatHex(frame).toUpperCase() + "  len=" + payload.length
                            + "  msg=\"" + message + "\"");

                    sequence++;
                    Thread.sleep(TX_INTERVAL_MS);
                }
            } catch (Exception ex) {
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

                if (data >= 0) {
                    byte b = (byte) data;
                    deframer.processByte(b);

                    // ===== PRINT POR BLOQUES =====
                    if (b == '\n') {
                        System.out.println(asciiBuffer);
                        asciiBuffer.setLength(0);
                    } else {
                        asciiBuffer.append((char) b);
                    }
                }

            } catch (Exception e) {
                System.err.println("[WARN] Read error: " + e.getMessage());
            }
        }
    }
}