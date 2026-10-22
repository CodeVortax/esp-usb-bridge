# Troubleshooting

## Wrong boot mode detected (0x13)

The chip answered but is running its normal program, so the automatic reset did not pull GPIO0 low. Hold the **BOOT** button while esptool prints `Connecting...`, then release it once writing starts. Or use `espb flash FILE --manual`. If you needed this once and it then worked unaided, retrying is enough: the first reset after plugging in can be missed.

## The bridge did not come up

Plug the board in before `espb start`. Android shows a USB permission dialog; tap OK within 45 seconds. Choose "Always" so it does not ask again. If the app does not open, start it by hand and tap Start.

## espb monitor prints a PermissionError on exit

Harmless. It is miniterm trying to wake the terminal on exit and Android refusing.

## "esptool: missing" in `espb doctor`

Run the install script again. It installs esptool with pip when Termux has no package for it.

## The board stops responding when the phone sleeps

Turn off battery optimisation for ESP USB Bridge and keep its notification visible.

## Still stuck

Open an issue with the output of `espb doctor`, the exact error, and the last lines of the app's log.
