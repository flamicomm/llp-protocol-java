/*
package com.flamingo.llp.test;

import com.fazecast.jSerialComm.SerialPort;
import com.flamingo.comm.llp.*;
import com.flamingo.llp.monitor.MonitorEventBus;
import com.flamingo.llp.monitor.MonitorServer;
import com.flamingo.llp.monitor.SSEHandler;
import com.flamingo.llp.monitor.model.Direction;
import com.flamingo.llp.monitor.model.FrameEvent;
import com.flamingo.llp.monitor.model.RawDataEvent;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.HexFormat;

public class SerialLLPWebMonitorTest {

    public static void main(String[] args) throws Exception {

        // ================= MONITOR =================
        MonitorEventBus bus = new MonitorEventBus();
        SSEHandler sse = new SSEHandler();
        MonitorServer server = new MonitorServer(3000);

        // Endpoint SSE
        server.getServer().createContext("/events", exchange -> {
            sse.handle(exchange);
        });

        // Conectar bus → SSE
        bus.subscribe(event -> {
            try {
                String json = serialize(event);
                sse.broadcast(json);
            } catch (Exception ignored) {
            }
        });

        server.start();

        // ================= SERIAL CONFIG =================
        SerialPort port = SerialPort.getCommPort("/dev/ttyUSB0");
        port.setBaudRate(9600);
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
        System.out.println("🌐 Open http://localhost:3000");
        System.out.println("⏳ Waiting for Arduino reset...");
        Thread.sleep(2000);

        InputStream in = port.getInputStream();
        OutputStream out = port.getOutputStream();

        // ================= LLP =================
        LLPParser parser = LLP.newParser();

        parser.addListener(new LLPParser.LLPFrameListener() {
            @Override
            public void onFrameReceived(LLPFrame frame) {
                bus.publish(new FrameEvent(Direction.RX, frame));
            }

            @Override
            public void onFrameError(byte errorCode) {
                // Podés agregar evento de error si querés
            }
        });

        // ================= RX THREAD =================
        Thread reader = new Thread(() -> {
            while (true) {
                try {
                    int data = in.read();

                    if (data >= 0) {
                        byte b = (byte) data;

                        // RAW RX
                        bus.publish(new RawDataEvent(Direction.RX, b));

                        // Parser
                        parser.processByte(b);
                    }

                } catch (Exception e) {
                    System.err.println("[ERROR] " + e.getMessage());
                }
            }
        });

        reader.setDaemon(true);
        reader.start();

        // ================= TX LOOP =================
        int id = 1;

        while (true) {

            // ===== PING =====
            byte[] ping = LLP.buildPing(id++);

            // RAW TX
            for (byte b : ping) {
                bus.publish(new RawDataEvent(Direction.TX, b));
            }

            out.write(ping);
            out.flush();

            Thread.sleep(3000);

            // ===== DATA =====
            LLPFrame outFrame = LLP.frameBuilder()
                    .id(id++)
                    .payload("Hello from Java".getBytes())
                    .type(LLPMessageType.DATA)
                    .buildFrame();


            byte[] data = LLP.buildFrame(outFrame.type(), outFrame.id(), outFrame.payload());

            for (byte b : data) {
                bus.publish(new RawDataEvent(Direction.TX, b));
            }

            out.write(data);
            out.flush();

            bus.publish(new FrameEvent(Direction.TX, outFrame));

            Thread.sleep(5000);
        }
    }

    // ================= SIMPLE JSON SERIALIZER =================
    private static String serialize(Object event) {

        if (event instanceof RawDataEvent e) {
            return String.format(
                    "{\"type\":\"raw\",\"dir\":\"%s\",\"hex\":\"%02X\",\"ts\":%d}",
                    e.getDirection(),
                    e.getData(),
                    e.getTimestamp()
            );
        }

        if (event instanceof FrameEvent e) {
            LLPFrame f = e.getFrame();

            return String.format(
                    "{\"type\":\"frame\",\"dir\":\"%s\",\"frameType\":\"0x%02X\",\"id\":%d,\"len\":%d,\"ts\":%d,\"payload\":\"%s\"}",
                    e.getDirection(),
                    f.type(),
                    f.id(),
                    f.payloadLength(),
                    e.getTimestamp(),
                    HexFormat.of().formatHex(f.payload())
            );
        }

        return "{}";
    }
}*/
