# Changelog

## 0.1.0
- First release: Android app that serves a USB-connected ESP32 as an RFC 2217 serial port on `127.0.0.1:2217`.
- `espb` Termux tool: `start`, `status`, `flash`, `erase`, `info`, `mac`, `monitor`, `log`, `doctor`.
- Works on a real ESP32 (CP210x): flashing verified with esptool 5.
- Python 3.13+ on Android compatibility (`sys.platform == "android"`) handled in `espb`.
- `espb flash --manual`, and the app log shows DTR/RTS activity.
- Auto-reset into the bootloader through DTR/RTS, baud-rate changes, read-only status port on `2218`.
