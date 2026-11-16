# Linux setup (uinput)

The receiver creates a **real kernel input device** through `/dev/uinput`.
Wayland and X11 compositors, SDL games, Steam and Proton see it as ordinary
keyboard/mouse hardware. No compositor API, driver package or virtual gamepad
is involved.

## 1. Requirements

- Linux with the `uinput` kernel module (`CONFIG_INPUT_UINPUT`; enabled on
  Arch/CachyOS, Fedora, Ubuntu, Debian, openSUSE and most gaming kernels).
- Write access to `/dev/uinput` for the user running the receiver.
- Rust toolchain to build (or a prebuilt receiver binary).

Check the module:

```bash
lsmod | grep '^uinput'
# if empty:
sudo modprobe uinput
```

Check the node:

```bash
ls -l /dev/uinput
# crw-rw---- 1 root input 10, 223 ...   ← group-writable is ideal
```

## 2. Grant permission without running as root

Choose **one** of the following.

### Option A — `input` group (simplest)

```bash
sudo usermod -aG input "$USER"
```

Log out and back in (or reboot) so the new group is in your session. Verify:

```bash
id -nG | tr ' ' '\n' | grep -x input
```

On distributions where `/dev/uinput` is `root:input 0660`, this is enough.
On stricter setups, use the udev rule as well.

### Option B — udev rule (recommended)

Create `/etc/udev/rules.d/85-phonecontroller-uinput.rules`:

```udev
KERNEL=="uinput", SUBSYSTEM=="misc", MODE="0660", GROUP="input", OPTIONS+="static_node=uinput"
```

Reload and apply:

```bash
sudo udevadm control --reload-rules
sudo udevadm trigger --subsystem-match=misc --name=uinput
ls -l /dev/uinput
```

The `static_node=uinput` option makes udev apply the rule even when the module
is loaded after udev starts.

### Option C — ACL for one user (no re-login)

```bash
sudo setfacl -m u:"$USER":rw /dev/uinput
```

This is lost on reboot unless persisted by a tmpfiles/udev rule. Prefer A or B.

**Do not run the receiver as root permanently.** It is unnecessary and
increases the impact of any receiver vulnerability.

## 3. Build and run

```bash
cd pc-receiver
cargo build --release

# Fixed PIN (recommended while testing)
./target/release/phonecontroller-receiver --pair
# → prints a random 6-digit PIN and listens on TCP/UDP 41000

# Or a known PIN / different ports
./target/release/phonecontroller-receiver \
  --pin 123456 --port 41000 --name "my-pc"
```

`--pin` and `--pair` are mutually exclusive in spirit: `--pin` fixes the PIN,
`--pair` generates a new random PIN each run. Running with no flag defaults to
`--pair`. `--open` disables PIN pairing (insecure; only for isolated labs).

Firewall (if enabled):

```bash
# nftables/ufw examples — allow the LAN, not the whole internet
sudo ufw allow 41000/tcp
sudo ufw allow 41000/udp
```

Discovery is a UDP broadcast; if the network blocks broadcasts, enter the PC's
IP manually in the app.

## 4. Verify injection

With the receiver running and the phone/simulator connected:

```bash
# List input devices and find the receiver's virtual device
libinput list-devices | grep -A4 -i phonecontroller

# Stream its events (replace eventN)
sudo evtest /dev/input/eventN

# Or watch compositor-level events
sudo libinput debug-events
```

Expected device name: `PhoneController Virtual Input`. Holding `W` in the app
must produce `KEY_W` down/up in `evtest`; mouse movement produces
`REL_X/REL_Y`.

Automated readback tests (no root needed when permissions are correct):

```bash
cd pc-receiver
cargo test -p input-linux -- --nocapture
```

## 5. Wayland, X11 and Steam/Proton

| Environment | Status | Notes |
|---|---|---|
| KDE/GNOME Wayland | Supported | uinput is kernel-level; the compositor reads the new evdev node |
| X11 / XWayland | Supported | Same evdev node; XWayland games receive it through the compositor |
| Native SDL games | Supported | SDL enumerates `/dev/input/event*`; no special flags |
| Steam + Proton | Supported | Proton's SDL/Wine input stack sees a normal keyboard/mouse |
| Flatpak/Steam Flatpak | Supported | Device appears on the host input bus, not inside the sandbox |
| Games with raw-input exclusive mode | Usually supported | The device is indistinguishable from hardware |
| Anti-cheat protected games | **Not guaranteed** | Some anti-cheats reject synthetic devices; no bypass is implemented |

If a game ignores the virtual keyboard, confirm it receives events at the
compositor level with `libinput debug-events` first. If yes, the issue is the
game's input mode (exclusive/raw), not the receiver.

## 6. Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `No such file or directory` for `/dev/uinput` | Module not loaded | `sudo modprobe uinput`; add `uinput` to `/etc/modules-load.d/` |
| `Permission denied` | No write access | Re-check group/udev/ACL; log out after `usermod` |
| Device appears, no key events | Wrong event node or window focus | Use `evtest`; focus the target window |
| Keys stick after killing the receiver | Receiver was SIGKILLed | `pkill phonecontroller-receiver` normally releases; on crash, release with `sudo evtest`/replug keyboard or reboot |
| Discovery finds nothing | Broadcast blocked / different subnet | Enter the PC IP manually; check firewall |
| Phone detects PC on Wi-Fi but not on the phone's hotspot | Firewall classifies the hotspot as a new zone, or the PC joined the wrong AP | Allow TCP/UDP 41000 on the hotspot interface (`sudo ufw allow in on wlan0 to any port 41000 proto tcp`, same for `udp`); see [`HOTSPOT.md`](HOTSPOT.md) |
| Phone connects, but every event drops | Stale session/seq or wrong PIN | Restart the receiver, reconnect; check receiver logs |
| Device disappears after receiver exit | Expected | uinput devices are owned by the process |

## 7. Uninstall the rule

```bash
sudo rm /etc/udev/rules.d/85-phonecontroller-uinput.rules
sudo udevadm control --reload-rules
```

Group membership can be removed with `sudo gpasswd -d "$USER" input`.
