// SPDX-License-Identifier: MIT
package com.codevortax.espusbbridge.core;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Listens on 127.0.0.1 and serves ONE RFC 2217 client at a time. A new client replaces the old
 * one (esptool reconnects a lot, and a dead connection must never block the next one).
 */
public final class BridgeServer {
    public interface Listener {
        void onLog(String line);
        void onClientChanged(boolean connected);
    }

    private final int requestedPort;
    private final int requestedStatusPort;
    private final String deviceLabel;
    private final SerialBackend backend;
    private final Listener listener;
    private final AtomicLong toDevice = new AtomicLong();
    private final AtomicLong fromDevice = new AtomicLong();

    private ServerSocket server;
    private ServerSocket statusServer;
    private Thread acceptor;
    private volatile boolean clientConnected;
    private volatile Rfc2217Session current;
    private volatile boolean stopped;

    /**
     * @param port       RFC 2217 port (0 = pick a free one)
     * @param statusPort read-only status port: connect, get one line, disconnect. It never touches
     *                   the serial session, so "espb status" cannot kick a flash in progress.
     */
    public BridgeServer(int port, int statusPort, String deviceLabel, SerialBackend backend, Listener listener) {
        this.requestedPort = port;
        this.requestedStatusPort = statusPort;
        this.deviceLabel = deviceLabel;
        this.backend = backend;
        this.listener = listener;
    }

    public void start() throws IOException {
        InetAddress local = InetAddress.getByName("127.0.0.1");
        server = new ServerSocket(requestedPort, 4, local);
        try {
            statusServer = new ServerSocket(requestedStatusPort, 4, local);
        } catch (IOException e) {
            server.close();
            throw e;
        }
        acceptor = new Thread(this::acceptLoop, "bridge-acceptor");
        acceptor.setDaemon(true);
        acceptor.start();
        Thread st = new Thread(this::statusLoop, "bridge-status");
        st.setDaemon(true);
        st.start();
        listener.onLog("Listening on 127.0.0.1:" + server.getLocalPort()
                + " (status on " + statusServer.getLocalPort() + ")");
    }

    public int getLocalPort() { return server.getLocalPort(); }
    public int getStatusPort() { return statusServer.getLocalPort(); }
    public long bytesToDevice() { return toDevice.get(); }
    public long bytesFromDevice() { return fromDevice.get(); }

    public void stop() {
        stopped = true;
        Rfc2217Session s = current;
        if (s != null) s.close();
        try { if (server != null) server.close(); } catch (IOException ignored) { }
        try { if (statusServer != null) statusServer.close(); } catch (IOException ignored) { }
    }

    /** One line: ESPBRIDGE ok device=<label> client=<0|1> to=<bytes> from=<bytes> */
    String statusLine() {
        return "ESPBRIDGE ok device=" + deviceLabel.replace(' ', '_') + " client=" + (clientConnected ? 1 : 0)
                + " to=" + toDevice.get() + " from=" + fromDevice.get() + "\n";
    }

    private void statusLoop() {
        while (!stopped) {
            try (Socket c = statusServer.accept()) {
                OutputStream o = c.getOutputStream();
                o.write(statusLine().getBytes("US-ASCII"));
                o.flush();
            } catch (IOException e) {
                if (stopped) break;
            }
        }
    }

    private void acceptLoop() {
        while (!stopped) {
            final Socket client;
            try {
                client = server.accept();
            } catch (IOException e) {
                break;
            }
            Rfc2217Session old = current;
            if (old != null) old.close();

            final Rfc2217Session session = new Rfc2217Session(client, backend, toDevice, fromDevice);
            current = session;
            Thread t = new Thread(() -> {
                listener.onLog("Client connected");
                clientConnected = true;
                listener.onClientChanged(true);
                session.run();
                if (current == session) {
                    current = null;
                    clientConnected = false;
                    listener.onClientChanged(false);
                }
                listener.onLog("Client disconnected");
            }, "bridge-session");
            t.setDaemon(true);
            t.start();
        }
    }
}
