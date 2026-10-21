# ESP USB Bridge

Flash and monitor an ESP32 from **Termux** on a phone, with no root.

Android does not let Termux touch USB devices, so a small app does it instead. It owns the USB port and shares it with Termux as a standard **RFC 2217** network serial port on `127.0.0.1:2217`. `esptool` and `pySerial` speak that natively, so flashing, chip info and a live serial monitor all work from the Termux command line.

```
ESP32 ──USB/OTG── [ ESP USB Bridge app ] ── 127.0.0.1:2217 ── Termux: espb / esptool / miniterm
```

The port is bound to localhost only, so nothing else on your network can reach it.

## Install

1. **App.** Download `esp-usb-bridge-debug-apk` from the latest [Actions run](../../actions) (or a release), unzip it and install the APK.
2. **Termux.** Run:
   ```sh
   curl -fsSL https://raw.githubusercontent.com/CodeVortax/esp-usb-bridge/main/termux/install.sh | bash
   ```
3. Plug the board in with a **data** USB cable and an OTG adapter. Allow USB access when Android asks.

## Use

```sh
espb start                  # opens the app and starts the bridge
espb status                 # bridge running? device? client connected?
espb flash firmware.bin     # merged image, address 0x0, 460800 baud
espb flash app.bin --addr 0x10000 --baud 115200
espb flash fw.bin --erase   # erase everything first
espb info                   # chip and flash details
espb mac
espb monitor                # interactive; Ctrl+] to quit
espb log --seconds 20 --out boot.log --reset   # timestamped lines, then exit
espb doctor                 # check the setup
```

`espb flash` starts the app by itself if it is not running. Use `--dry-run` to see the exact `esptool` command.

You can also call the tools directly:

```sh
python -m esptool --port rfc2217://127.0.0.1:2217 chip-id
python -m serial.tools.miniterm rfc2217://127.0.0.1:2217 115200
```

## Flashing the WiFi repeater firmware

Build the merged image (`pio run -t webflash` in [esp32-wifi-repeater-manage](https://github.com/CodeVortax/esp32-wifi-repeater-manage)), copy it to the phone, then `espb flash esp32-wifi-repeater-merged.bin`.

## Troubleshooting

- **"Connecting..." never finishes:** hold the BOOT button on the board while it connects, or lower the speed with `--baud 115200`.
- **No device found:** use a data cable, check the OTG adapter, and make sure you tapped OK on the USB permission prompt.
- **Bridge stops when the screen is off:** allow the app to run in the background (disable battery optimisation for it). The app keeps a foreground notification while running.
- **`espb: command not found`:** rerun the install script.
- **Only one client at a time:** a new connection replaces the old one, so close `espb monitor` before flashing.

## How it works

The app implements the server side of RFC 2217 (Telnet COM-PORT-OPTION): baud rate, data format, DTR/RTS (used for the automatic bootloader reset) and purge commands are forwarded to the USB serial chip through [usb-serial-for-android](https://github.com/mik3y/usb-serial-for-android). Supported chips include CP210x, CH340/CH34x, FTDI, PL2303 and the ESP32's built-in USB. Port `2218` is a read-only status port used by `espb status`, so checking never disturbs a running session.

The protocol layer is tested in CI against a real pySerial RFC 2217 client.

## Status

Beta. The protocol layer is tested against pySerial; behaviour on real boards and chips is still being confirmed. Issues welcome.

## License

MIT. See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
