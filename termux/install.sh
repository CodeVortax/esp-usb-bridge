#!/data/data/com.termux/files/usr/bin/bash
# Installs `espb` plus esptool and pyserial in Termux.
#
#   curl -fsSL https://raw.githubusercontent.com/CodeVortax/esp-usb-bridge/main/termux/install.sh | bash
#
# Uses Termux packages (apt) when the package exists, and only falls back to pip when it does not.
# Anything already installed is skipped. No repo refresh is done; run `pkg update` yourself if a package is not found.
set -e

have_pkg() { dpkg -s "$1" >/dev/null 2>&1; }
in_repo()  { apt-cache show "$1" >/dev/null 2>&1; }
have_mod() { python -c "import $1" >/dev/null 2>&1; }

# apt_get <pkg>...: install the ones that are not installed yet; returns 1 if any is missing from the repo
apt_get() {
    local missing=()
    for p in "$@"; do
        have_pkg "$p" && { echo "  $p: already installed"; continue; }
        in_repo "$p" || return 1
        missing+=("$p")
    done
    [ ${#missing[@]} -eq 0 ] && return 0
    echo "  installing: ${missing[*]}"
    apt-get install -y "${missing[@]}"
}

pip_get() {
    pip install --upgrade "$@" 2>/dev/null \
        || pip install --upgrade --break-system-packages "$@"
}

echo "==> Base packages"
apt_get python curl || { echo "python/curl not found in the package cache. Run: pkg update"; exit 1; }

echo "==> cryptography (needed by esptool)"
if have_mod cryptography; then echo "  already available"; else apt_get python-cryptography || true; fi

echo "==> pyserial"
if have_mod serial; then
    echo "  already installed"
else
    apt_get python-pyserial || apt_get pyserial || pip_get pyserial
fi

echo "==> esptool"
if have_mod esptool; then
    echo "  already installed"
else
    apt_get esptool || true
    have_mod esptool || pip_get esptool || pip_get "esptool<5"
fi

echo "==> espb"
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
