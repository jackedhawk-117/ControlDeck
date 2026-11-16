# Phone hotspot mode

PhoneController works when the PC is joined to the **phone's Wi-Fi hotspot**
instead of a shared router. The phone is both the access point and the
controller; no internet connection or external router is required.

```text
PC  ──Wi-Fi──▶  phone hotspot (AP)  ◀──UDP/TCP 41000──  PhoneController app
```

The PC is typically assigned an address such as `192.168.43.42`, the phone's AP
interface is `192.168.43.1`, and the receiver listens on all interfaces. The
existing PIN pairing and AES-256-GCM session encryption are unchanged.

## Setup

1. **Enable the hotspot on the phone.** Settings → Network & internet → Hotspot
   & tethering (or the quick-settings tile). Use WPA2/WPA3; an open hotspot lets
   anyone nearby attempt pairing.
2. **Join the hotspot from the PC.**
3. **Start the receiver on the PC** (and allow it through the firewall):

   ```bash
   cd pc-receiver
   ./target/release/phonecontroller-receiver --pair
   ```

   - Linux: allow UDP and TCP `41000` from the hotspot subnet (`ufw allow
     41000/tcp`, `ufw allow 41000/udp`, or the equivalent nftables rule).
   - Windows: the hotspot is often classified as a **Public** network. Allow
     the receiver on Public, or add inbound rules for TCP/UDP 41000.
4. **Scan from the app.** Tap *Scan local network*. A successful result looks
   like:

   ```text
   Probed: Phone hotspot 192.168.43.1
   cachyos — 192.168.43.42 · Phone hotspot
   ```

5. **Select the PC and pair** with the receiver's PIN.
6. If discovery is blocked, enter the PC's hotspot address manually. Find it
   with `ip -4 addr` (Linux) or `ipconfig` (Windows).

## How discovery reaches the PC

The app enumerates every active, non-loopback IPv4 interface and opens one UDP
socket bound to that interface's address. For each interface it sends
`DISCOVER` to both `255.255.255.255` and the interface's **directed broadcast**
address (for example `192.168.43.255`). The receiver's wildcard UDP socket
replies unicast with `ANNOUNCE` to the phone.

This avoids the common failure mode where the phone's default route is mobile
data while the PC is only reachable over the AP interface (`wlan0`, `ap0`, or
`swlan0`). Scan results report the interface that reached each PC so the path
in use is visible.

No protocol change is involved; only the choice of local socket/broadcast
destination changed. The PC receiver needs no special mode.

## Troubleshooting

| Symptom | Cause / fix |
|---|---|
| Scan shows only the normal Wi-Fi address | Hotspot interface is not up yet. Toggle the hotspot on and scan again. |
| Hotspot interface probed, no PC listed | Receiver not running; PC firewall blocking UDP 41000; PC joined a different network. |
| PC listed, pairing times out | TCP 41000 blocked; wrong PIN; receiver started with a non-default bind address. |
| Discovery worked on Wi-Fi, not hotspot | A VPN on the phone or PC can override routes. Disable it for the test. |
| Inputs stop when the screen is off | Keep the app in the foreground during play; the gameplay screen holds the screen awake. |

## Security notes

- The hotspot passphrase protects the wireless link; PIN pairing and session
  encryption still apply on top.
- Turn the hotspot off when finished, especially in public places.
- The receiver must never be exposed to the internet; the hotspot mode is
  local-only by design.
