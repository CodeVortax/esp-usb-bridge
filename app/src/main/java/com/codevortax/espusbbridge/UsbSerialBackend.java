// SPDX-License-Identifier: MIT
package com.codevortax.espusbbridge;

import com.codevortax.espusbbridge.core.SerialBackend;
import com.hoho.android.usbserial.driver.UsbSerialPort;

import java.io.IOException;
import java.util.Arrays;

/** Adapts a usb-serial-for-android port to the bridge's SerialBackend. */
final class UsbSerialBackend implements SerialBackend {
    private static final int WRITE_TIMEOUT_MS = 5000;

    private final UsbSerialPort port;

    UsbSerialBackend(UsbSerialPort port) { this.port = port; }

    @Override
    public void setParameters(int baud, int dataBits, int stopBits, int parity) throws IOException {
        port.setParameters(baud, dataBits, stopBits, parity);
    }

    @Override public void setDtr(boolean on) throws IOException { port.setDTR(on); }
    @Override public void setRts(boolean on) throws IOException { port.setRTS(on); }

    @Override
    public void setBreak(boolean on) throws IOException {
        try { port.setBreak(on); } catch (UnsupportedOperationException ignored) { }
    }

    @Override
    public int read(byte[] buf, int timeoutMs) throws IOException {
        return port.read(buf, timeoutMs);
    }

    @Override
    public void write(byte[] buf, int off, int len) throws IOException {
        byte[] data = (off == 0 && len == buf.length) ? buf : Arrays.copyOfRange(buf, off, off + len);
        port.write(data, WRITE_TIMEOUT_MS);
    }

    @Override
    public void purge(boolean rx, boolean tx) throws IOException {
        try { port.purgeHwBuffers(tx, rx); } catch (UnsupportedOperationException ignored) { }
    }
}
