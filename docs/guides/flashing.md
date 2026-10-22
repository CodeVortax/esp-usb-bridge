# Flashing guide

## A merged image (recommended)

A merged image contains bootloader, partition table, app and filesystem. Write it at `0x0`:

```sh
espb flash esp32-wifi-repeater-master.bin
```

## Separate files

Write each file at its own address, for example:

```sh
espb flash bootloader.bin --addr 0x1000
espb flash partitions.bin --addr 0x8000
espb flash firmware.bin   --addr 0x10000
```

## Options

| Option | Meaning |
|---|---|
| `--baud N` | Flash speed. Default 460800. Use 115200 if it is unreliable. |
| `--chip NAME` | `esp32`, `esp32s3`, `esp32c3`... Default: detect. |
| `--erase` | Erase the whole flash first (wipes saved settings). |
| `--manual` | No automatic reset. Hold BOOT, tap EN, release BOOT when writing starts. |
| `--dry-run` | Print the esptool command only. |

After a successful flash the board is reset. Watch it with `espb monitor` or `espb log --seconds 30 --reset`.
