// SPDX-License-Identifier: MIT
package com.codevortax.espusbbridge;

import java.util.ArrayDeque;

/** What the screen shows. Written by the service, read by the activity. */
final class BridgeState {
    static volatile boolean running;
    static volatile boolean clientConnected;
    static volatile String device = "-";
    static volatile String status = "Stopped";
    static volatile long toDevice;
    static volatile long fromDevice;

    private static final int MAX_LINES = 120;
    private static final ArrayDeque<String> LOG = new ArrayDeque<>();

    private BridgeState() { }

    static synchronized void log(String line) {
        if (LOG.size() >= MAX_LINES) LOG.removeFirst();
        LOG.addLast(line);
    }

    static synchronized String logText() {
        StringBuilder sb = new StringBuilder();
        for (String s : LOG) sb.append(s).append('\n');
        return sb.toString();
    }

    static void reset() {
        running = false;
        clientConnected = false;
        device = "-";
        status = "Stopped";
        toDevice = 0;
        fromDevice = 0;
    }
}
