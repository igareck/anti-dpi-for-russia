#!/bin/sh
set -eu
igareck_adb="${IGARECK_ADB:-adb}"
"$igareck_adb" devices -l
"$igareck_adb" -d get-state
printf '%s\n' 'In Igareck, open Network module > Activate via USB > Start over USB.' \
    'Wait for Waiting for the USB command, then press Enter.'
read -r igareck_confirmation
exec "$igareck_adb" -d shell 'content read --uri content://com.igareck.antidpi.usb/start | sh'
