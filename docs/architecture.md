# Architecture

```
ESP32 ──USB── UsbSerialBackend ── Rfc2217Session ── BridgeServer ── 127.0.0.1:2217 ── esptool / miniterm
                (usb-serial-for-android)                      └─ 127.0.0.1:2218 ── espb status
```

- **BridgeService**: a foreground service (type `connectedDevice`) that opens the USB device, starts the server and holds a wake lock while running.
- **BridgeServer**: listens on localhost only. One client at a time; a new connection replaces the old one. Port 2218 answers one status line and closes.
- **Rfc2217Session**: Telnet parser and RFC 2217 server. Handles baud rate, data size, parity, stop bits, DTR/RTS/BREAK, purge, and acknowledges each command. `0xFF` is escaped in the data stream.
- **SerialBackend**: a tiny interface over the USB port, which lets the protocol be tested without a phone.

## Why RFC 2217

`esptool` resets the chip with DTR and RTS and changes the baud rate mid-flash. A plain TCP pipe cannot carry those. RFC 2217 can, and pySerial and esptool support it natively, so Termux needs no custom client.

## Tests

`InteropHarness` runs the real server against a pySerial client (`tools/interop/client.py`) with a fake serial port: baud changes, the DTR/RTS reset sequence, binary data with `0xFF`, 300 KB round trip, purge and the status line.
