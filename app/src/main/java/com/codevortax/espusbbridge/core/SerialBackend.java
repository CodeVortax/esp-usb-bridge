// SPDX-License-Identifier: MIT
package com.codevortax.espusbbridge.core;

import java.io.IOException;

/**
 * The serial port behind the bridge. The Android app implements this with
 * usb-serial-for-android; the tests implement it with a loopback.
 *
 * Constants match usb-serial-for-android so the Android adapter is a thin pass-through:
 * parity 0 none, 1 odd, 2 even, 3 mark, 4 space; stop bits 1, 2, 3 (= 1.5).
 */
public interface SerialBackend {
    void setParameters(int baud, int dataBits, int stopBits, int parity) throws IOException;
    void setDtr(boolean on) throws IOException;
    void setRts(boolean on) throws IOException;
    void setBreak(boolean on) throws IOException;

    /** Reads up to buf.length bytes. Returns 0 on timeout, throws IOException when the device is gone. */
    int read(byte[] buf, int timeoutMs) throws IOException;

    /** Writes every byte or throws. */
    void write(byte[] buf, int off, int len) throws IOException;

    void purge(boolean rx, boolean tx) throws IOException;
}
