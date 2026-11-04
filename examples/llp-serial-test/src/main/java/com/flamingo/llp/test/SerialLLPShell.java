package com.flamingo.llp.test;

import com.fazecast.jSerialComm.SerialPort;
import com.flamingo.comm.llp.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.Scanner;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Agent-first LLP Serial Shell.
 *
 * <p>Designed primarily for AI agents and automated testing. Supports three modes:</p>
 * <ol>
 *     <li><b>Batch string mode</b> (recommended for agents):
 *         Pass a command string as the first argument:
 *         <pre>java SerialLLPShell 'connect /dev/ttyUSB0; wait 2000; llpascii Hello; wait 3000; rx; quit'</pre>
 *     </li>
 *     <li><b>Stdin pipe mode</b>:
 *         Pipe commands via stdin. The shell exits on EOF:
 *         <pre>echo -e "connect /dev/ttyUSB0\nllpascii Hello\nwait 3000\nrx\nquit" | java SerialLLPShell</pre>
 *     </li>
 *     <li><b>Interactive mode</b>:
 *         Run from a terminal for manual exploration:
 *         <pre>java SerialLLPShell --interactive</pre>
 *     </li>
 * </ol>
 *
 * <h2>All Commands</h2>
 * <pre>
 *   connect &lt;port&gt; [baud]   Open serial port (default baud: 115200)
 *   disconnect              Close serial port
 *   ports                   List available serial ports
 *   wait &lt;ms&gt;               Pause N milliseconds (useful between TX and RX)
 *   sendhex &lt;hex&gt;           Send raw hex bytes
 *   sendascii &lt;text&gt;        Send ASCII text
 *   sendbytes &lt;b1&gt; &lt;b2&gt;..   Send individual bytes (decimal/hex/char)
 *   llp &lt;hexpayload&gt;        Build and send an LLP frame from hex payload
 *   llpascii &lt;text&gt;         Build and send LLP frame from ASCII payload
 *   llpping                 Send a simple LLP ping frame
 *   rx                      Print RX log buffer
 *   clear                   Clear RX buffer
 *   help                    Show help
 *   quit                    Exit shell
 * </pre>
 *
 * <p>Expected Arduino sketch: examples/serial_echo/serial_echo.ino</p>
 */
public class SerialLLPShell {

    private static final int DEFAULT_BAUD = 115200;
    private static final int CONNECT_DELAY_MS = 1500;
    private static final int RX_POLL_MS = 50;

    private final Scanner scanner;
    private SerialPort port;
    private InputStream in;
    private OutputStream out;
    private Thread rxThread;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private final StringBuilder asciiLineBuffer = new StringBuilder();
    private final StringBuilder rxLog = new StringBuilder();
    private static final int MAX_RX_LOG_LINES = 200;

    private LLPIncrementalParser parser;
    private int llpChannel = 0;

    /**
     * Main entry.
     *
     * <p>Usage:</p>
     * <ul>
     *     <li>{@code java SerialLLPShell} — batch mode, reads commands from stdin</li>
     *     <li>{@code java SerialLLPShell '<cmd1>; <cmd2>; ...'} — batch string mode</li>
     *     <li>{@code java SerialLLPShell --interactive} — interactive terminal mode</li>
     * </ul>
     */
    public static void main(String[] args) {
        SerialLLPShell shell;
        if (args.length > 0 && !"--interactive".equals(args[0])) {
            // Batch string mode: split by semicolons, trim, execute
            String batch = String.join(" ", args);
            String[] commands = batch.split(";");
            shell = new SerialLLPShell(null);
            shell.runBatch(commands);
        } else {
            boolean interactive = args.length > 0 && "--interactive".equals(args[0]);
            shell = new SerialLLPShell(System.in);
            if (interactive) {
                shell.runInteractive();
            } else {
                shell.runBatchFromStdin();
            }
        }
    }

    private SerialLLPShell(InputStream inputStream) {
        this.scanner = (inputStream != null) ? new Scanner(inputStream) : null;
    }

    // =====================================================================
    // RUN MODES
    // =====================================================================

    /** Batch mode: execute a pre-built array of command strings. */
    private void runBatch(String[] commands) {
        System.out.println("=== LLP Serial Shell (Batch Mode) ===");
        for (String raw : commands) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            System.out.println("llp-shell> " + line);
            if (!execute(line)) break;
        }
        shutdownGracefully();
    }

    /** Batch mode: read commands from stdin until EOF. */
    private void runBatchFromStdin() {
        System.out.println("=== LLP Serial Shell (Stdin Pipe Mode) ===");
        while (scanner.hasNextLine()) {
            String line = scanner.nextLine().trim();
            if (line.isEmpty()) continue;
            System.out.println("llp-shell> " + line);
            if (!execute(line)) break;
        }
        shutdownGracefully();
    }

    /** Interactive mode: prompt and read from terminal. */
    private void runInteractive() {
        System.out.println("═══════════════════════════════════════════════════════════════");
        System.out.println("  LLP Serial Shell — Interactive Mode");
        System.out.println("═══════════════════════════════════════════════════════════════");
        System.out.println("Type 'help' for commands.\n");
        while (true) {
            System.out.print("llp-shell> ");
            if (!scanner.hasNextLine()) {
                break; // EOF
            }
            String line = scanner.nextLine().trim();
            if (line.isEmpty()) continue;
            if (!execute(line)) break;
        }
        shutdownGracefully();
    }

    /** Parse a single command line and execute it. Returns false to signal exit. */
    private boolean execute(String line) {
        String[] parts = line.split("\\s+", 2);
        String cmd = parts[0].toLowerCase();
        String rest = parts.length > 1 ? parts[1] : "";
        String[] args = rest.isEmpty() ? new String[0] : rest.split("\\s+");

        try {
            switch (cmd) {
                case "help"        -> help();
                case "quit", "exit" -> { quit(); return false; }
                case "ports"       -> listPorts();
                case "connect"     -> connect(args);
                case "disconnect"  -> disconnect();
                case "wait"        -> doWait(args);
                case "sendhex"     -> sendHex(args);
                case "sendascii"   -> sendAscii(args);
                case "sendbytes"   -> sendBytes(args);
                case "llp"         -> sendLlpHex(args);
                case "llpascii"    -> sendLlpAscii(args);
                case "llpchan"     -> setLlpChannel(args);
                case "llpping"     -> sendLlpPing();
                case "rx"          -> showRx();
                case "clear"       -> clearRx();
                default            -> System.out.println("Unknown command: " + cmd + ". Type 'help' for help.");
            }
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
        }
        return true;
    }

    // =====================================================================
    // COMMANDS
    // =====================================================================

    private void help() {
        System.out.println("""
            COMMANDS
            ──────────────────────────────────────────────────────────────────────
            CONNECT
              connect <port> [baud]  Open serial port (default: 115200)
              disconnect             Close serial port
              ports                  List available serial ports

            TIMING
              wait <ms>              Pause N milliseconds

            SEND RAW
              sendhex <hex>          Send raw hex bytes
              sendascii <text>       Send ASCII text
              sendbytes <b1> <b2>..  Send individual bytes (0xFF, 255, 'A')

            SEND LLP FRAMES
              llp <hex>              Build an LLP frame from hex payload
              llpascii <text>        Build an LLP frame with ASCII payload
              llpchan <n>            Set the channel for the commands above (0-255)
              llpping                Send a ping LLP frame

            RECEIVE
              rx                     Print RX log
              clear                  Clear RX log

            MISC
              help                   Show this help
              quit                   Exit shell
            ──────────────────────────────────────────────────────────────────────
            """);
    }

    private void listPorts() {
        SerialPort[] ports = SerialPort.getCommPorts();
        System.out.println("Available serial ports:");
        for (SerialPort p : ports) {
            System.out.println("  " + p.getSystemPortName()
                    + "  (" + p.getDescriptivePortName() + ")");
        }
    }

    private void connect(String[] args) {
        if (args.length == 0) {
            System.err.println("Usage: connect <port> [baud]");
            return;
        }
        String portName = args[0];
        int baud = args.length > 1 ? Integer.parseInt(args[1]) : DEFAULT_BAUD;

        disconnect();

        port = SerialPort.getCommPort(portName);
        port.setBaudRate(baud);
        port.setNumDataBits(8);
        port.setNumStopBits(SerialPort.ONE_STOP_BIT);
        port.setParity(SerialPort.NO_PARITY);
        port.setComPortTimeouts(SerialPort.TIMEOUT_READ_BLOCKING, 0, 0);

        if (!port.openPort()) {
            System.err.println("Failed to open port: " + portName);
            port = null;
            return;
        }

        System.out.println("Connected to " + port.getSystemPortName() + " at " + baud + " baud");
        System.out.println("Waiting for device reset...");
        sleep(CONNECT_DELAY_MS);

        in = port.getInputStream();
        out = port.getOutputStream();

        parser = LLP.incrementalParser()
                .capacity(LLP.MAX_CAPACITY)
                .timeoutMs(1000)
                .build();

        running.set(true);
        startRxThread();

        System.out.println("Ready.");
    }

    private void disconnect() {
        running.set(false);
        if (rxThread != null) {
            rxThread.interrupt();
            rxThread = null;
        }
        if (port != null && port.isOpen()) {
            port.closePort();
            System.out.println("Disconnected.");
        }
        port = null;
        in = null;
        out = null;
    }

    private void quit() {
        disconnect();
        System.out.println("Goodbye!");
    }

    private void shutdownGracefully() {
        disconnect();
    }

    private void doWait(String[] args) {
        if (args.length == 0) {
            System.err.println("Usage: wait <ms>");
            return;
        }
        long ms = Long.parseLong(args[0]);
        sleep(ms);
    }

    private void sendHex(String[] args) throws IOException {
        ensureConnected();
        if (args.length == 0) {
            System.err.println("Usage: sendhex <hexstring>");
            return;
        }
        String hex = String.join("", args).replaceAll("\\s+", "");
        byte[] data = HexFormat.of().parseHex(hex);
        out.write(data);
        out.flush();
        System.out.println("[TX HEX] " + hex.toUpperCase());
    }

    private void sendAscii(String[] args) throws IOException {
        ensureConnected();
        if (args.length == 0) {
            System.err.println("Usage: sendascii <text>");
            return;
        }
        String text = String.join(" ", args);
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        out.write(data);
        out.flush();
        System.out.println("[TX ASCII] \"" + text + "\"");
    }

    private void sendBytes(String[] args) throws IOException {
        ensureConnected();
        if (args.length == 0) {
            System.err.println("Usage: sendbytes <b1> <b2> ...");
            return;
        }
        byte[] data = new byte[args.length];
        for (int i = 0; i < args.length; i++) {
            data[i] = parseByte(args[i]);
        }
        out.write(data);
        out.flush();
        System.out.println("[TX BYTES] " + HexFormat.of().formatHex(data).toUpperCase());
    }

    private void sendLlpHex(String[] args) throws IOException {
        ensureConnected();
        if (args.length == 0) {
            System.err.println("Usage: llp <hexpayload>");
            return;
        }
        String hex = String.join("", args).replaceAll("\\s+", "");
        byte[] frame = LLP.encode(llpChannel, HexFormat.of().parseHex(hex));
        out.write(frame);
        out.flush();
        System.out.println("[TX LLP] channel=" + llpChannel
                + "  frame=" + HexFormat.of().formatHex(frame).toUpperCase());
    }

    private void sendLlpAscii(String[] args) throws IOException {
        ensureConnected();
        if (args.length == 0) {
            System.err.println("Usage: llpascii <text>");
            return;
        }
        String text = String.join(" ", args);
        byte[] appPayload = text.getBytes(StandardCharsets.UTF_8);
        byte[] frame = LLP.encode(llpChannel, appPayload);
        out.write(frame);
        out.flush();
        System.out.println("[TX LLP] channel=" + llpChannel
                + "  frame=" + HexFormat.of().formatHex(frame).toUpperCase()
                + "  payload=\"" + text + "\"");
    }

    private void sendLlpPing() throws IOException {
        ensureConnected();
        byte[] frame = LLP.encode(llpChannel, "PING".getBytes(StandardCharsets.UTF_8));
        out.write(frame);
        out.flush();
        System.out.println("[TX LLP PING] channel=" + llpChannel
                + "  frame=" + HexFormat.of().formatHex(frame).toUpperCase());
    }

    private void setLlpChannel(String[] args) {
        if (args.length == 0) {
            System.out.println("LLP channel: " + llpChannel);
            return;
        }
        try {
            int value = Integer.parseInt(args[0]);
            if (value < 0 || value > 255) {
                System.err.println("channel must be 0..255");
                return;
            }
            llpChannel = value;
            System.out.println("LLP channel set to " + llpChannel);
        } catch (NumberFormatException e) {
            System.err.println("channel must be a number 0..255");
        }
    }

    private void showRx() {
        synchronized (rxLog) {
            if (rxLog.isEmpty()) {
                System.out.println("(no received data)");
            } else {
                System.out.println("--- RX LOG ---");
                System.out.print(rxLog);
                System.out.println("--- END RX LOG ---");
            }
        }
    }

    private void clearRx() {
        synchronized (rxLog) {
            rxLog.setLength(0);
        }
        asciiLineBuffer.setLength(0);
        System.out.println("Receive buffer cleared.");
    }

    private void ensureConnected() {
        if (!running.get() || port == null || !port.isOpen()) {
            throw new IllegalStateException("Not connected. Use 'connect <port>' first.");
        }
    }

    // =====================================================================
    // RX THREAD
    // =====================================================================

    private void startRxThread() {
        rxThread = new Thread(() -> {
            while (running.get() && !Thread.currentThread().isInterrupted()) {
                try {
                    if (in.available() > 0) {
                        int data = in.read();
                        if (data < 0) {
                            running.set(false);
                            break;
                        }
                        byte b = (byte) data;
                        handleRxByte(b);
                    } else {
                        sleep(RX_POLL_MS);
                    }
                } catch (Exception e) {
                    if (running.get()) {
                        System.err.println("[RX ERROR] " + e.getMessage());
                    }
                }
            }
        }, "SerialLLP-RX");
        rxThread.setDaemon(true);
        rxThread.start();
    }

    private void handleRxByte(byte b) {
        parser.feed(b);

        List<LLPFrame> frames = parser.pollFrames();
        for (LLPFrame frame : frames) {
            byte[] payload = frame.payloadBytes();
            String hex = HexFormat.of().formatHex(payload).toUpperCase();
            String ascii = new String(payload, StandardCharsets.UTF_8);
            logRx("[RX LLP FRAME] channel=" + frame.channel()
                    + " len=" + payload.length
                    + " crc=0x" + Integer.toHexString(frame.crc())
                    + " hex=" + hex + " ascii=\"" + ascii + "\"");
        }

        List<LLPError> errors = parser.pollErrors();
        for (LLPError err : errors) {
            logRx("[RX LLP ERROR] " + err.wireName() + " code=" + err.code()
                    + " desc=" + err.description());
        }

        if (b == '\n') {
            String line = asciiLineBuffer.toString();
            if (!line.isEmpty()) {
                logRx("[RX ASCII] " + line);
            }
            asciiLineBuffer.setLength(0);
        } else if (b >= 0x20 && b < 0x7F) {
            asciiLineBuffer.append((char) b);
        } else if (b != '\r') {
            asciiLineBuffer.setLength(0);
        }
    }

    private void logRx(String line) {
        synchronized (rxLog) {
            rxLog.append(line).append('\n');
            trimRxLog();
        }
        System.out.println(line);
    }

    private void trimRxLog() {
        while (rxLog.length() > MAX_RX_LOG_LINES * 100) {
            int idx = rxLog.indexOf("\n", 10);
            if (idx < 0) break;
            rxLog.delete(0, idx + 1);
        }
    }

    private byte parseByte(String s) {
        String clean = s.trim();
        if (clean.startsWith("0x") || clean.startsWith("0X")) {
            return (byte) Integer.parseInt(clean.substring(2), 16);
        } else if (clean.startsWith("'") && clean.endsWith("'") && clean.length() >= 2) {
            return (byte) clean.charAt(1);
        } else {
            return (byte) Integer.parseInt(clean, 10);
        }
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
