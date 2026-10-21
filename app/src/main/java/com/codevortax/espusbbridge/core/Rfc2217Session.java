// SPDX-License-Identifier: MIT
package com.codevortax.espusbbridge.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One client connection speaking Telnet + RFC 2217 (COM-PORT-OPTION) to a serial port.
 *
 * Written against the behaviour of pySerial's rfc2217 client, which is what esptool uses
 * for "rfc2217://host:port". The parts that matter for flashing:
 *  - BINARY, SGA and COM-PORT options are accepted in both directions
 *  - baud / data size / parity / stop size changes are applied and ACKed with the new value
 *  - DTR / RTS / BREAK (SET_CONTROL) are applied and ACKed with the same value
 *  - PURGE_DATA is applied and ACKed
 *  - 0xFF is escaped as 0xFF 0xFF in both directions
 */
public final class Rfc2217Session {
    // Telnet
    private static final int IAC = 255, DONT = 254, DO = 253, WONT = 252, WILL = 251, SB = 250, SE = 240;
    private static final int OPT_BINARY = 0, OPT_SGA = 3, OPT_COM_PORT = 44;
    // RFC 2217 client -> server commands. The server answers with command + 100.
    private static final int SET_BAUDRATE = 1, SET_DATASIZE = 2, SET_PARITY = 3, SET_STOPSIZE = 4,
            SET_CONTROL = 5, SET_LINESTATE_MASK = 10, SET_MODEMSTATE_MASK = 11, PURGE_DATA = 12;

    private enum PState { DATA, IAC, VERB, SB, SB_IAC }

    private final Socket socket;
    private final SerialBackend port;
    private final AtomicLong toDevice;
    private final AtomicLong fromDevice;
    private final Object outLock = new Object();
    private final boolean[] we = new boolean[256];     // options we have agreed to do
    private final boolean[] they = new boolean[256];   // options the client does

    private InputStream in;
    private OutputStream out;
    private volatile boolean closed;

    // Current line settings (what the client last asked for).
    private int baud = 115200, dataBits = 8, stopBits = 1, parity = 0;
    private boolean dtr, rts, brk;
    private int appliedBaud = -1, appliedData = -1, appliedStop = -1, appliedParity = -1;

    /** Optional diagnostics sink (shown in the app's log). */
    public volatile java.util.function.Consumer<String> logger;

    private void log(String m) { java.util.function.Consumer<String> l = logger; if (l != null) l.accept(m); }

    public Rfc2217Session(Socket socket, SerialBackend port, AtomicLong toDevice, AtomicLong fromDevice) {
        this.socket = socket;
        this.port = port;
        this.toDevice = toDevice;
        this.fromDevice = fromDevice;
    }

    /** Blocks until the client disconnects or the serial port fails. */
    public void run() {
        Thread pump = null;
        try {
            socket.setTcpNoDelay(true);
            in = socket.getInputStream();
            out = socket.getOutputStream();
            pump = new Thread(this::pumpSerialToSocket, "bridge-serial-reader");
            pump.setDaemon(true);
            pump.start();
            readLoop();
        } catch (IOException ignored) {
            // client went away: normal
        } finally {
            close();
            if (pump != null) pump.interrupt();
        }
    }

    public void close() {
        closed = true;
        try { socket.close(); } catch (IOException ignored) { }
    }

    // ---- serial -> socket --------------------------------------------------

    private void pumpSerialToSocket() {
        byte[] buf = new byte[4096];
        byte[] esc = new byte[buf.length * 2];
        try {
            while (!closed) {
                int n = port.read(buf, 50);
                if (n <= 0) continue;
                int o = 0;
                for (int i = 0; i < n; i++) {
                    esc[o++] = buf[i];
                    if (buf[i] == (byte) IAC) esc[o++] = (byte) IAC;
                }
                synchronized (outLock) {
                    out.write(esc, 0, o);
                    out.flush();
                }
                fromDevice.addAndGet(n);
            }
        } catch (IOException e) {
            // serial port failed (unplugged) or socket closed: end the session
        } finally {
            close();
        }
    }

    // ---- socket -> serial (Telnet parser) -----------------------------------

    private void readLoop() throws IOException {
        byte[] buf = new byte[8192];
        byte[] data = new byte[buf.length];
        PState st = PState.DATA;
        int verb = 0;
        ByteArrayOutputStream sb = new ByteArrayOutputStream();

        int n;
        while (!closed && (n = in.read(buf)) >= 0) {
            int d = 0;
            for (int i = 0; i < n; i++) {
                int b = buf[i] & 0xFF;
                switch (st) {
                    case DATA:
                        if (b == IAC) st = PState.IAC; else data[d++] = (byte) b;
                        break;
                    case IAC:
                        if (b == IAC) { data[d++] = (byte) IAC; st = PState.DATA; }
                        else if (b == WILL || b == WONT || b == DO || b == DONT) { verb = b; st = PState.VERB; }
                        else if (b == SB) { sb.reset(); st = PState.SB; }
                        else st = PState.DATA;               // NOP, GA, ... ignored
                        break;
                    case VERB:
                        if (d > 0) { writeToSerial(data, d); d = 0; }
                        negotiate(verb, b);
                        st = PState.DATA;
                        break;
                    case SB:
                        if (b == IAC) st = PState.SB_IAC; else sb.write(b);
                        break;
                    case SB_IAC:
                        if (b == IAC) { sb.write(IAC); st = PState.SB; }
                        else if (b == SE) {
                            if (d > 0) { writeToSerial(data, d); d = 0; }
                            subnegotiation(sb.toByteArray());
                            st = PState.DATA;
                        } else st = PState.SB;               // malformed: keep collecting
                        break;
                }
            }
            if (d > 0) writeToSerial(data, d);
        }
    }

    private void writeToSerial(byte[] data, int len) throws IOException {
        port.write(data, 0, len);
        toDevice.addAndGet(len);
    }

    // ---- Telnet option negotiation ------------------------------------------

    private static boolean supported(int opt) {
        return opt == OPT_BINARY || opt == OPT_SGA || opt == OPT_COM_PORT;
    }

    private void negotiate(int verb, int opt) throws IOException {
        switch (verb) {
            case DO:
                if (!supported(opt)) send(WONT, opt);
                else if (!we[opt]) { we[opt] = true; send(WILL, opt); }
                break;
            case DONT:
                if (we[opt]) { we[opt] = false; send(WONT, opt); }
                break;
            case WILL:
                if (!supported(opt)) send(DONT, opt);
                else if (!they[opt]) { they[opt] = true; send(DO, opt); }
                break;
            case WONT:
                if (they[opt]) { they[opt] = false; send(DONT, opt); }
                break;
            default:
                break;
        }
    }

    private void send(int verb, int opt) throws IOException {
        synchronized (outLock) {
            out.write(new byte[]{(byte) IAC, (byte) verb, (byte) opt});
            out.flush();
        }
    }

    // ---- RFC 2217 ------------------------------------------------------------

    private void subnegotiation(byte[] sb) throws IOException {
        if (sb.length < 2 || (sb[0] & 0xFF) != OPT_COM_PORT) return;
        int cmd = sb[1] & 0xFF;
        switch (cmd) {
            case SET_BAUDRATE: {
                if (sb.length < 6) return;
                int requested = ((sb[2] & 0xFF) << 24) | ((sb[3] & 0xFF) << 16) | ((sb[4] & 0xFF) << 8) | (sb[5] & 0xFF);
                if (requested != 0) {
                    int old = baud;
                    baud = requested;
                    if (!applyLine()) baud = old;          // ACK with the old value: the client reports the failure
                }
                ack(cmd, new byte[]{(byte) (baud >>> 24), (byte) (baud >>> 16), (byte) (baud >>> 8), (byte) baud});
                break;
            }
            case SET_DATASIZE: {
                if (sb.length < 3) return;
                int v = sb[2] & 0xFF;
                if (v != 0) { int old = dataBits; dataBits = v; if (!applyLine()) dataBits = old; }
                ack(cmd, new byte[]{(byte) dataBits});
                break;
            }
            case SET_PARITY: {
                if (sb.length < 3) return;
                int v = sb[2] & 0xFF;                       // RFC: 1 none, 2 odd, 3 even, 4 mark, 5 space
                if (v != 0) { int old = parity; parity = v - 1; if (!applyLine()) parity = old; }
                ack(cmd, new byte[]{(byte) (parity + 1)});
                break;
            }
            case SET_STOPSIZE: {
                if (sb.length < 3) return;
                int v = sb[2] & 0xFF;                       // RFC: 1 one, 2 two, 3 one and a half
                if (v != 0) { int old = stopBits; stopBits = v; if (!applyLine()) stopBits = old; }
                ack(cmd, new byte[]{(byte) stopBits});
                break;
            }
            case SET_CONTROL: {
                if (sb.length < 3) return;
                int v = sb[2] & 0xFF;
                int reply = setControl(v);
                ack(cmd, new byte[]{(byte) reply});
                break;
            }
            case SET_LINESTATE_MASK:
            case SET_MODEMSTATE_MASK:
                if (sb.length >= 3) ack(cmd, new byte[]{sb[2]});
                break;
            case PURGE_DATA: {
                if (sb.length < 3) return;
                int v = sb[2] & 0xFF;                       // 1 receive, 2 transmit, 3 both
                try { port.purge((v & 1) != 0, (v & 2) != 0); } catch (IOException ignored) { }
                ack(cmd, new byte[]{(byte) v});
                break;
            }
            default:
                break;                                      // flow control suspend/resume etc.: nothing to do
        }
    }

    /** Applies a SET_CONTROL value and returns the value to ACK with. */
    private int setControl(int v) {
        try {
            switch (v) {
                case 8:  dtr = true;  port.setDtr(true);  log("DTR on");  return 8;
                case 9:  dtr = false; port.setDtr(false); log("DTR off"); return 9;
                case 11: rts = true;  port.setRts(true);  log("RTS on");  return 11;
                case 12: rts = false; port.setRts(false); log("RTS off"); return 12;
                case 5:  brk = true;  port.setBreak(true);  return 5;
                case 6:  brk = false; port.setBreak(false); return 6;
                case 7:  return dtr ? 8 : 9;                // request DTR state
                case 10: return rts ? 11 : 12;              // request RTS state
                case 4:  return brk ? 5 : 6;                // request BREAK state
                case 0: case 1: case 2: case 3:             // flow control: USB bridges do it themselves
                default: return v;
            }
        } catch (IOException | RuntimeException e) {
            log("control " + v + " FAILED: " + e);
            return v;
        }
    }

    private boolean applyLine() {
        // pySerial sends baud, data size, parity and stop size one after another when it opens;
        // only touch the USB chip when something really changed.
        if (baud == appliedBaud && dataBits == appliedData && stopBits == appliedStop && parity == appliedParity) return true;
        try {
            port.setParameters(baud, dataBits, stopBits, parity);
            appliedBaud = baud; appliedData = dataBits; appliedStop = stopBits; appliedParity = parity;
            return true;
        } catch (IOException | RuntimeException e) {
            log("setParameters " + baud + " FAILED: " + e);
            return false;
        }
    }

    private void ack(int cmd, byte[] value) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(IAC); b.write(SB); b.write(OPT_COM_PORT); b.write(cmd + 100);
        for (byte x : value) { b.write(x & 0xFF); if ((x & 0xFF) == IAC) b.write(IAC); }
        b.write(IAC); b.write(SE);
        synchronized (outLock) {
            out.write(b.toByteArray());
            out.flush();
        }
    }
}
