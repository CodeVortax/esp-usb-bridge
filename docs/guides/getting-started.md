# Getting started

1. Install the APK (see the [README](../../README.md#install)).
2. In Termux run the install script, then `espb doctor`. Everything should say installed.
3. Plug in the board with a data cable and an OTG adapter.
4. Run `espb start` and tap OK when Android asks for USB access.
5. `espb status` should show `Bridge: running` and your chip's USB id (for example `10C4:EA60` is a CP210x).
6. `espb flash firmware.bin`.

Tip: run `termux-setup-storage` once so your Downloads folder is available at `~/storage/downloads`.
