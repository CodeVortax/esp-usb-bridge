#!/data/data/com.termux/files/usr/bin/bash
# Installs `espb` (and esptool + pyserial) in Termux.
#
#   curl -fsSL https://raw.githubusercontent.com/CodeVortax/esp-usb-bridge/main/termux/install.sh | bash
#
set -e

echo "==> Installing Python packages"
pkg install -y python python-cryptography curl

# esptool needs cryptography, which is slow to build here, so Termux's prebuilt one is installed first.
pip install --upgrade esptool pyserial 2>/dev/null \
  || pip install --upgrade --break-system-packages esptool pyserial \
  || { echo "esptool 5 failed to install, trying esptool 4"; pip install --break-system-packages "esptool<5" pyserial; }

echo "==> Installing espb"
DEST="${PREFIX:-/data/data/com.termux/files/usr}/bin/espb"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")" 2>/dev/null && pwd || true)"
if [ -n "$HERE" ] && [ -f "$HERE/espb" ]; then
    install -m 755 "$HERE/espb" "$DEST"
else
    curl -fsSL https://raw.githubusercontent.com/CodeVortax/esp-usb-bridge/main/termux/espb -o "$DEST"
    chmod 755 "$DEST"
fi

echo
espb doctor || true
echo
echo "Next: install the ESP USB Bridge app, plug in the board, then run:"
echo "  espb start && espb flash firmware.bin"
