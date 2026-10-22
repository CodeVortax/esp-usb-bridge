# Contributing

Small, focused changes are easiest to review.

- The protocol code in `app/src/main/java/com/codevortax/espusbbridge/core/` must stay free of Android classes so it can be tested on a normal JVM.
- Anything that changes protocol behaviour needs a case in `tools/interop/client.py` and `InteropHarness`.
- Keep `espb` dependency-light: Python standard library plus esptool and pySerial.

## Run the protocol test

```sh
pip install pyserial
gradle :app:testDebugUnitTest
```

## Bug reports

Include your phone model and Android version, the board and USB chip (`espb status` shows the id), the output of `espb doctor`, and the failing command's full output.
