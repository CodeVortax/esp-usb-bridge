// SPDX-License-Identifier: MIT
package com.codevortax.espusbbridge.core;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Runs the real bridge against a fake serial port and talks to it with the real pySerial
 * RFC 2217 client (tools/interop/client.py). Used by the unit test and runnable by hand:
 *
 *   PYSERIAL_PATH=/path/to/pyserial java -cp out com.codevortax.espusbbridge.core.InteropHarness
 */
public final class InteropHarness {

    /** A serial port that loops every written byte back and records what was asked of it. */
    static final class LoopbackBackend implements SerialBackend {
        final LinkedBlockingQueue<byte[]> queue = new LinkedBlockingQueue<>();
        final List<String> events = new ArrayList<>();
        private byte[] pending = new byte[0];

        private void log(String s) { synchronized (events) { events.add(s); } }
        List<String> events() { synchronized (events) { return new ArrayList<>(events); } }

        @Override public void setParameters(int baud, int d, int s, int p) { log("params " + baud + " " + d + " " + s + " " + p); }
        @Override public void setDtr(boolean on) { log("dtr " + on); }
        @Override public void setRts(boolean on) { log("rts " + on); }
        @Override public void setBreak(boolean on) { log("break " + on); }
        @Override public void purge(boolean rx, boolean tx) { log("purge " + rx + " " + tx); }

        @Override public void write(byte[] buf, int off, int len) {
            byte[] copy = new byte[len];
            System.arraycopy(buf, off, copy, 0, len);
            queue.add(copy);
        }

        @Override public int read(byte[] buf, int timeoutMs) throws IOException {
            try {
                if (pending.length == 0) {
                    byte[] next = queue.poll(timeoutMs, TimeUnit.MILLISECONDS);
                    if (next == null) return 0;
                    pending = next;
                }
                int n = Math.min(buf.length, pending.length);
                System.arraycopy(pending, 0, buf, 0, n);
                byte[] rest = new byte[pending.length - n];
                System.arraycopy(pending, n, rest, 0, rest.length);
                pending = rest;
                return n;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted");
            }
        }
    }

    public static void runAll() throws Exception {
        LoopbackBackend backend = new LoopbackBackend();
        BridgeServer server = new BridgeServer(0, 0, "TEST:0001", backend, new BridgeServer.Listener() {
            @Override public void onLog(String line) { System.out.println("[bridge] " + line); }
            @Override public void onClientChanged(boolean connected) { }
        });
        server.start();
        try {
            File script = new File("tools/interop/client.py");
            if (!script.exists()) script = new File("../tools/interop/client.py");
            if (!script.exists()) throw new AssertionError("tools/interop/client.py not found (run from the repo root)");

            ProcessBuilder pb = new ProcessBuilder("python3", script.getPath(), String.valueOf(server.getLocalPort()));
            pb.redirectErrorStream(true);
            Map<String, String> env = pb.environment();
            String extra = System.getenv("PYSERIAL_PATH");
            if (extra != null && !extra.isEmpty()) env.put("PYTHONPATH", extra);

            Process p = pb.start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) { output.append(line).append('\n'); System.out.println("[client] " + line); }
            }
            if (!p.waitFor(120, TimeUnit.SECONDS)) { p.destroyForcibly(); throw new AssertionError("client timed out"); }
            if (p.exitValue() != 0 || !output.toString().contains("CLIENT_OK"))
                throw new AssertionError("client failed (exit " + p.exitValue() + "):\n" + output);

            // What the fake port was asked to do, in order.
            List<String> ev = backend.events();
            System.out.println("[events] " + ev);
            require(ev, "params 460800 8 1 0");
            require(ev, "params 921600 8 1 0");
            // esptool's ClassicReset-style sequence must reach the port in this exact order.
            List<String> seq = java.util.Arrays.asList("dtr false", "rts true", "dtr true", "rts false", "dtr false");
            if (java.util.Collections.indexOfSubList(ev, seq) < 0)
                throw new AssertionError("DTR/RTS reset sequence not applied in order: " + ev);
            require(ev, "purge true false");
            require(ev, "purge false true");
            // The status port answers with one line and never disturbs the serial session.
            try (java.net.Socket sp = new java.net.Socket("127.0.0.1", server.getStatusPort());
                 BufferedReader sr = new BufferedReader(new InputStreamReader(sp.getInputStream()))) {
                String line = sr.readLine();
                System.out.println("[status] " + line);
                if (line == null || !line.startsWith("ESPBRIDGE ok device=TEST:0001 client=0"))
                    throw new AssertionError("unexpected status line: " + line);
            }
            if (server.bytesToDevice() < 300000) throw new AssertionError("bytesToDevice too small: " + server.bytesToDevice());
            if (server.bytesFromDevice() < 300000) throw new AssertionError("bytesFromDevice too small: " + server.bytesFromDevice());
        } finally {
            server.stop();
        }
    }

    private static void require(List<String> events, String wanted) {
        if (!events.contains(wanted)) throw new AssertionError("expected event \"" + wanted + "\" in " + events);
    }

    public static void main(String[] args) throws Exception {
        runAll();
        System.out.println("INTEROP OK");
    }
}
