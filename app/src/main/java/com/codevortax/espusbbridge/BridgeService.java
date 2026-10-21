// SPDX-License-Identifier: MIT
package com.codevortax.espusbbridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import androidx.core.content.ContextCompat;

import com.codevortax.espusbbridge.core.BridgeServer;
import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;

import java.io.IOException;

/**
 * Foreground service that opens the USB serial port and serves it as RFC 2217 on 127.0.0.1:2217.
 * Termux then uses it with:  esptool --port rfc2217://127.0.0.1:2217 ...
 */
public class BridgeService extends Service implements BridgeServer.Listener {
    static final String ACTION_START = "com.codevortax.espusbbridge.START";
    static final String ACTION_STOP = "com.codevortax.espusbbridge.STOP";
    static final String EXTRA_DEVICE = "device";
    static final int PORT = 2217;
    static final int STATUS_PORT = 2218;

    private static final String CHANNEL_ID = "bridge";
    private static final int NOTIFICATION_ID = 1;

    private final Object lock = new Object();
    private BridgeServer server;
    private UsbSerialPort port;
    private UsbDeviceConnection connection;
    private PowerManager.WakeLock wakeLock;
    private Thread statsThread;
    private String deviceName;
    private volatile boolean stopping;

    private final BroadcastReceiver detachReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            UsbDevice d = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (d != null && d.getDeviceName().equals(deviceName)) {
                BridgeState.log("USB device unplugged");
                stopBridge();
            }
        }
    };

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopBridge();
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }

        // Must be called right after startForegroundService().
        Notification n = buildNotification("Starting...");
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }

        final String name = intent.getStringExtra(EXTRA_DEVICE);
        if (BridgeState.running) return START_NOT_STICKY;      // already serving
        stopping = false;
        BridgeState.reset();
        BridgeState.running = true;
        BridgeState.status = "Starting...";
        new Thread(() -> openAndServe(name), "bridge-start").start();
        return START_NOT_STICKY;
    }

    private void openAndServe(String name) {
        try {
            UsbManager um = (UsbManager) getSystemService(Context.USB_SERVICE);
            UsbDevice device = name == null ? null : um.getDeviceList().get(name);
            if (device == null) throw new IOException("USB device not found");
            UsbSerialDriver driver = UsbSerialProber.getDefaultProber().probeDevice(device);
            if (driver == null) throw new IOException("Not a supported USB serial chip");
            if (!um.hasPermission(device)) throw new IOException("No USB permission");

            UsbDeviceConnection c = um.openDevice(device);
            if (c == null) throw new IOException("Could not open the USB device");
            UsbSerialPort p = driver.getPorts().get(0);
            p.open(c);
            p.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
            // Leave both lines low so the board is not held in reset or in download mode.
            try { p.setDTR(false); p.setRTS(false); } catch (IOException | UnsupportedOperationException ignored) { }

            String label = String.format("%04X:%04X", device.getVendorId(), device.getProductId());
            BridgeServer s = new BridgeServer(PORT, STATUS_PORT, label, new UsbSerialBackend(p), this);
            s.start();

            synchronized (lock) {
                deviceName = name;
                connection = c;
                port = p;
                server = s;
            }
            ContextCompat.registerReceiver(this, detachReceiver,
                    new IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED), ContextCompat.RECEIVER_NOT_EXPORTED);
            acquireWakeLock();

            BridgeState.device = label + " " + driver.getClass().getSimpleName().replace("SerialDriver", "");
            BridgeState.status = "Ready on 127.0.0.1:" + PORT;
            BridgeState.log("USB " + BridgeState.device + " open");
            updateNotification("Ready on 127.0.0.1:" + PORT);
            startStatsThread();
        } catch (IOException | RuntimeException e) {
            BridgeState.log("Error: " + e.getMessage());
            BridgeState.status = "Error: " + e.getMessage();
            stopBridge();
            // stopBridge() resets the status; show the reason once more
            BridgeState.status = "Error: " + e.getMessage();
        }
    }

    private void startStatsThread() {
        statsThread = new Thread(() -> {
            while (!stopping) {
                BridgeServer s;
                synchronized (lock) { s = server; }
                if (s == null) break;
                BridgeState.toDevice = s.bytesToDevice();
                BridgeState.fromDevice = s.bytesFromDevice();
                try { Thread.sleep(500); } catch (InterruptedException e) { break; }
            }
        }, "bridge-stats");
        statsThread.setDaemon(true);
        statsThread.start();
    }

    // ---- BridgeServer.Listener ------------------------------------------------

    @Override public void onLog(String line) { BridgeState.log(line); }

    @Override
    public void onClientChanged(boolean connected) {
        BridgeState.clientConnected = connected;
        BridgeState.status = connected ? "Client connected" : "Ready on 127.0.0.1:" + PORT;
        updateNotification(BridgeState.status);
    }

    // ---- teardown ---------------------------------------------------------------

    private void stopBridge() {
        stopping = true;
        BridgeServer s;
        UsbSerialPort p;
        UsbDeviceConnection c;
        synchronized (lock) {
            s = server; p = port; c = connection;
            server = null; port = null; connection = null;
        }
        if (s != null) s.stop();
        if (p != null) { try { p.close(); } catch (IOException | RuntimeException ignored) { } }
        if (c != null) { try { c.close(); } catch (RuntimeException ignored) { } }
        try { unregisterReceiver(detachReceiver); } catch (IllegalArgumentException ignored) { }
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        BridgeState.reset();
        BridgeState.log("Stopped");
        stopForeground(true);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        if (!stopping) stopBridge();
        super.onDestroy();
    }

    private void acquireWakeLock() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "espusbbridge:bridge");
        wakeLock.acquire(6L * 60 * 60 * 1000);                 // never leak it: 6 hours at most
    }

    // ---- notification -----------------------------------------------------------

    private Notification buildNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL_ID,
                getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW));

        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, BridgeService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, R.drawable.ic_stat), "Stop", stop).build())
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.notify(NOTIFICATION_ID, buildNotification(text));
    }
}
