package com.flamingo.llp.test;

import com.fazecast.jSerialComm.SerialPort;

import java.io.InputStream;

public class SerialTest {

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

        // ================= RX THREAD =================
        StringBuilder asciiBuffer = new StringBuilder();

        while (!Thread.currentThread().isInterrupted()) {
            try {
                int data = in.read();

                if (data >= 0) {
                    byte b = (byte) data;

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
