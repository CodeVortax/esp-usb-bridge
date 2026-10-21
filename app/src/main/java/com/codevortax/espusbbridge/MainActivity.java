// SPDX-License-Identifier: MIT
package com.codevortax.espusbbridge;

import android.Manifest;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialProber;

import java.util.List;
import java.util.Locale;

/** One screen: Start / Stop, what is connected, and what Termux should run. */
public class MainActivity extends Activity {
    private static final String ACTION_USB_PERMISSION = "com.codevortax.espusbbridge.USB_PERMISSION";
    static final String EXTRA_AUTOSTART = "autostart";

    private static final int BLUE = 0xFF4285F4;
    private static final int GREEN = 0xFF34A853;
    private static final int RED = 0xFFEA4335;
    private static final int CARD = 0xFF171B23;
    private static final int MUTED = 0xFF98A1B3;

    private TextView statusView, deviceView, trafficView, logView;
    private Button toggle;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() {
        @Override public void run() { render(); ui.postDelayed(this, 500); }
    };

    private final BroadcastReceiver permissionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!ACTION_USB_PERMISSION.equals(intent.getAction())) return;
            if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                UsbDevice d = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (d != null) launchService(d);
            } else {
                BridgeState.log("USB permission was denied");
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        ContextCompat.registerReceiver(this, permissionReceiver, new IntentFilter(ACTION_USB_PERMISSION),
                ContextCompat.RECEIVER_NOT_EXPORTED);
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    /** "espb start" in Termux opens us with autostart=true; plugging the board in also starts the bridge. */
    private void handleIntent(Intent intent) {
        if (intent == null) return;
        boolean attached = UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(intent.getAction());
        if (attached || intent.getBooleanExtra(EXTRA_AUTOSTART, false)) {
            intent.removeExtra(EXTRA_AUTOSTART);
            if (!BridgeState.running) startBridge();
        }
    }

    @Override protected void onResume() { super.onResume(); ui.post(refresh); }
    @Override protected void onPause() { ui.removeCallbacks(refresh); super.onPause(); }

    @Override
    protected void onDestroy() {
        try { unregisterReceiver(permissionReceiver); } catch (IllegalArgumentException ignored) { }
        super.onDestroy();
    }

    // ---- actions ------------------------------------------------------------------

    private void onToggle() {
        if (BridgeState.running) {
            startService(new Intent(this, BridgeService.class).setAction(BridgeService.ACTION_STOP));
        } else {
            startBridge();
        }
    }

    private void startBridge() {
        UsbManager um = (UsbManager) getSystemService(Context.USB_SERVICE);
        List<UsbSerialDriver> drivers = UsbSerialProber.getDefaultProber().findAllDrivers(um);
        if (drivers.isEmpty()) {
            BridgeState.log("No supported USB serial device found. Plug the board in with a data cable and an OTG adapter.");
            BridgeState.status = "No device";
            return;
        }
        UsbDevice device = drivers.get(0).getDevice();
        if (um.hasPermission(device)) {
            launchService(device);
            return;
        }
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
        PendingIntent pi = PendingIntent.getBroadcast(this, 0,
                new Intent(ACTION_USB_PERMISSION).setPackage(getPackageName()), flags);
        BridgeState.log("Asking for USB permission...");
        um.requestPermission(device, pi);
    }

    private void launchService(UsbDevice device) {
        Intent i = new Intent(this, BridgeService.class)
                .setAction(BridgeService.ACTION_START)
                .putExtra(BridgeService.EXTRA_DEVICE, device.getDeviceName());
        ContextCompat.startForegroundService(this, i);
    }

    // ---- UI -----------------------------------------------------------------------

    private void render() {
        boolean running = BridgeState.running;
        statusView.setText(BridgeState.status);
        statusView.setTextColor(BridgeState.status.startsWith("Error") ? RED
                : BridgeState.clientConnected ? GREEN : running ? BLUE : Color.WHITE);
        deviceView.setText("Device: " + BridgeState.device);
        trafficView.setText(String.format(Locale.US, "To board: %s   From board: %s",
                bytes(BridgeState.toDevice), bytes(BridgeState.fromDevice)));
        toggle.setText(running ? "Stop bridge" : "Start bridge");
        setRounded(toggle, running ? 0xFF2A3040 : BLUE);
        String text = BridgeState.logText();
        if (!text.contentEquals(logView.getText())) logView.setText(text);
    }

    private static String bytes(long n) {
        if (n < 1024) return n + " B";
        if (n < 1048576) return String.format(Locale.US, "%.1f KB", n / 1024.0);
        return String.format(Locale.US, "%.1f MB", n / 1048576.0);
    }

    private void buildUi() {
        int pad = dp(20);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad + dp(12), pad, pad);

        root.addView(text("ESP USB Bridge", 24, Color.WHITE, true));
        root.addView(text("Lets Termux flash and monitor an ESP32 plugged into this phone.", 14, MUTED, false), lpTop(dp(4)));

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        setRounded(card, CARD);
        statusView = text("Stopped", 20, Color.WHITE, true);
        deviceView = text("Device: -", 14, MUTED, false);
        trafficView = text("", 14, MUTED, false);
        card.addView(statusView);
        card.addView(deviceView, lpTop(dp(6)));
        card.addView(trafficView, lpTop(dp(2)));
        root.addView(card, lpTop(dp(20)));

        toggle = new Button(this);
        toggle.setAllCaps(false);
        toggle.setTextColor(Color.WHITE);
        toggle.setTextSize(16);
        toggle.setOnClickListener(v -> onToggle());
        root.addView(toggle, lpTop(dp(14)));

        root.addView(text("In Termux", 16, Color.WHITE, true), lpTop(dp(24)));
        TextView cmds = text("espb status\nespb flash firmware.bin\nespb monitor", 14, 0xFFCFD6E4, false);
        cmds.setTypeface(Typeface.MONOSPACE);
        cmds.setTextIsSelectable(true);
        cmds.setPadding(dp(12), dp(10), dp(12), dp(10));
        setRounded(cmds, CARD);
        root.addView(cmds, lpTop(dp(8)));
        root.addView(text("Keep this app running while you flash. Pick \"Always\" when Android asks about the USB device.",
                13, MUTED, false), lpTop(dp(8)));

        root.addView(text("Log", 16, Color.WHITE, true), lpTop(dp(24)));
        logView = text("", 12, MUTED, false);
        logView.setTypeface(Typeface.MONOSPACE);
        root.addView(logView, lpTop(dp(6)));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(root);
        setContentView(scroll);
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private LinearLayout.LayoutParams lpTop(int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.topMargin = top;
        return p;
    }

    private void setRounded(View v, int color) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(14));
        v.setBackground(d);
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
