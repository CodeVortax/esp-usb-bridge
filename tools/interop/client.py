#!/usr/bin/env python3
"""Drives the bridge with pySerial's RFC 2217 client (the one esptool uses) and checks it.

Usage: client.py PORT
Prints CLIENT_OK on success; any failure raises and exits non-zero.
"""
import os
import sys
import time

import serial


def read_exact(ser, n, timeout=20.0):
    buf = bytearray()
    end = time.time() + timeout
    while len(buf) < n and time.time() < end:
        chunk = ser.read(n - len(buf))
        buf.extend(chunk)
    return bytes(buf)


def main():
    port = int(sys.argv[1])
    url = "rfc2217://127.0.0.1:%d" % port

    # 1. open: Telnet + COM-PORT negotiation, initial line settings, DTR/RTS, purge
    ser = serial.serial_for_url(url, baudrate=115200, timeout=2)

    # 2. baud change after open (esptool does this once it is connected); must be ACKed
    ser.baudrate = 460800
    assert ser.baudrate == 460800

    # 3. esptool "ClassicReset"-like DTR/RTS sequence
    ser.dtr = False
    ser.rts = True
    time.sleep(0.02)
    ser.dtr = True
    ser.rts = False
    time.sleep(0.02)
    ser.dtr = False

    # 4. data both ways, including the byte that Telnet has to escape (0xFF)
    payload = bytes([0x00, 0x01, 0xFF, 0xC0, 0xFF, 0xFF, 0x7F, 0x20]) * 8
    ser.write(payload)
    got = read_exact(ser, len(payload))
    assert got == payload, "small loopback mismatch: %r" % got

    # 5. a firmware-sized transfer, random bytes, must arrive intact and in order
    big = os.urandom(300000)
    for i in range(0, len(big), 16384):
        ser.write(big[i:i + 16384])
    got = read_exact(ser, len(big), timeout=60)
    assert got == big, "large loopback mismatch (%d of %d bytes)" % (len(got), len(big))

    ser.reset_input_buffer()
    ser.reset_output_buffer()
    ser.close()

    # 6. a second client right after the first: the bridge must accept it
    ser = serial.serial_for_url(url + "?ign_set_control", baudrate=921600, timeout=2)
    ser.write(b"\xff")
    assert read_exact(ser, 1) == b"\xff"
    ser.close()

    print("CLIENT_OK")


if __name__ == "__main__":
    main()
