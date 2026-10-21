# Changelog

## 0.1.0-beta
- First release: Android app that serves a USB-connected ESP32 as an RFC 2217 serial port on `127.0.0.1:2217`.
- `espb` Termux tool: `start`, `status`, `flash`, `erase`, `info`, `mac`, `monitor`, `log`, `doctor`.
- Auto-reset into the bootloader through DTR/RTS, baud-rate changes, read-only status port on `2218`.
