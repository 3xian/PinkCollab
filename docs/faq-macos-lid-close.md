# macOS: Gateway stops working after closing the lid

## Why the phone cannot connect

PinkCollab Gateway can run as a [background service](deployment.md#run-as-a-background-service), so closing the terminal does not stop it. On macOS, the service is a launchd user agent that starts at login.

By default, closing a MacBook's lid puts macOS to sleep. System sleep suspends Gateway execution and Tailscale network connectivity, so the phone cannot reach the Gateway while the Mac is asleep. A launchd background service cannot bypass system sleep. Running in the background requires the host to remain awake.

## Solution 1 (recommended): keep the Mac awake on power

1. Connect the MacBook to power.
2. Open **System Settings > Battery > Options** and enable **Prevent automatic sleeping on power adapter when the display is off**. The location and wording may vary by macOS version; see Apple's [sleep and wake settings](https://support.apple.com/en-us/guide/mac-help/mchle41a6ccd/mac).
3. Keep the lid open and leave the Mac awake while you need remote access. The display can turn off without putting the system to sleep.

This setting prevents automatic idle sleep on power; it does not by itself prevent sleep when you close the lid. For operation with the lid closed, use one of the following solutions.

## Solution 2: use macOS clamshell mode

Use the MacBook with its lid closed and an external display connected. This setup requires:

- An external display.
- Power from a power adapter or a display that supplies power to the Mac.
- An external keyboard and mouse or trackpad.

Connect power, the display, and the input devices before closing the lid. On Apple silicon Macs, approve the accessories while the Mac is unlocked if prompted; see Apple's [accessory guidance for using a Mac laptop with the lid closed](https://support.apple.com/en-us/102282).

Keep the automatic sleep setting from Solution 1 enabled for unattended remote access, and verify that the phone can connect with the lid closed.

## Solution 3 (advanced): disable system sleep with pmset

For an advanced setup, use `pmset` to disable system sleep:

```sh
sudo pmset -a disablesleep 1
```

Check the setting:

```sh
pmset -g | grep -i SleepDisabled
```

`SleepDisabled 1` indicates that sleep is disabled. This is a system-wide setting, including battery operation. Verify connectivity on your Mac and macOS version before relying on it for unattended access. The `disablesleep` option and `SleepDisabled` output are present in Apple's [PowerManagement source](https://github.com/aosm/PowerManagement/blob/master/pmset/pmset.c); this is an advanced system setting rather than a PinkCollab service option.

**Warning:** Do not put the MacBook in a bag with sleep disabled. It may continue running with the lid closed, generating heat and draining the battery. Keep it on a ventilated surface and connected to power during use.

Restore normal sleep behavior when remote access is no longer needed, and before putting the MacBook in a bag:

```sh
sudo pmset -a disablesleep 0
```

Run the status command above again and confirm `SleepDisabled 0`.

## Why caffeinate is not a reliable lid-close solution

`caffeinate` can prevent idle sleep, but it is not a reliable way to prevent sleep caused by closing the lid. Apple distinguishes [idle sleep from forced sleep](https://developer.apple.com/library/archive/qa/qa1340/_index.html), which includes closing a laptop's lid. Use the solutions above for remote Gateway availability.

## Troubleshooting

Wake the Mac, then run these commands under the same user and data directory as the Gateway service:

```sh
pinkcollab status
pinkcollab doctor
pinkcollab service status
tailscale status
```

Check Gateway health, the background service state, and the Tailscale connection. After applying a solution, try connecting from the phone again with the Mac in the intended operating state. Diagnostics run while the Mac is awake do not prove that it stays reachable with the lid closed.

If the phone still cannot connect while the Mac is awake, follow [deployment troubleshooting](deployment.md#troubleshooting-setup).
