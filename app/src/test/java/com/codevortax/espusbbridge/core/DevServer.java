// SPDX-License-Identifier: MIT
package com.codevortax.espusbbridge.core;

/**
 * Runs the bridge on this computer with a loopback "board", so you can try the Termux side
 * (espb status / log / monitor) without any hardware:
 *
 *   java -cp out com.codevortax.espusbbridge.core.DevServer 2217 2218
 */
public final class DevServer {
    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 2217;
        int status = args.length > 1 ? Integer.parseInt(args[1]) : 2218;
        InteropHarness.LoopbackBackend board = new InteropHarness.LoopbackBackend();
        BridgeServer server = new BridgeServer(port, status, "DEV:0000", board, new BridgeServer.Listener() {
            @Override public void onLog(String line) { System.out.println("[bridge] " + line); }
            @Override public void onClientChanged(boolean connected) { }
        });
        server.start();
        // Pretend to be a board that prints a line every second.
        Thread chatter = new Thread(() -> {
            int n = 0;
            while (true) {
                try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
                byte[] msg = ("hello from fake board " + (n++) + "\r\n").getBytes();
                board.write(msg, 0, msg.length);
            }
        });
        chatter.setDaemon(true);
        chatter.start();
        Thread.currentThread().join();
    }
}
