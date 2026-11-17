# PhoneController Protocol v1

Normative reference for the wire format shared by the Android app, the Rust
receiver, and the simulator. Any implementation change must land in this
document first.

All integers are **little-endian**. All packets share a fixed 10-byte header.
Bytes on the wire are binary unless noted.

---

## 1. Transport & ports

| Channel | Protocol | Default port | Use |
|---|---|---|---|
| Control | TCP | 41000 | pairing, auth, heartbeats, reset, profile metadata |
| Input + discovery | UDP | 41000 | DISCOVER/ANNOUNCE, INPUT_DELTA/SNAPSHOT, STATS |

One TCP socket per session. One UDP socket on each side serves discovery and
input simultaneously. Max UDP datagram: **1400 bytes** total (header +
payload). Larger datagrams are invalid and dropped.

## 2. Packet header (10 bytes)

| Offset | Type | Field | Notes |
|---|---|---|---|
| 0 | u8 | magic0 | `'P'` = 0x50 |
| 1 | u8 | magic1 | `'C'` = 0x43 |
| 2 | u8 | version | 1 |
| 3 | u8 | kind | see §4 |
| 4 | u16 | payload_len | plaintext payload byte count (before encryption) |
| 6 | u32 | seq | stream sequence number (see nonce rules, §6) |
| 10 | — | payload | `payload_len` bytes (may be encrypted, §6) |

`payload_len` describes the *plaintext* payload. After encryption the on-wire
payload is longer (nonce implicit, tag appended). Receivers validate:
magic, version, kind, and `payload_len` bounds before parsing.

## 3. Key code space

Key codes are Linux evdev u16 codes (`input-event-codes.h`). Common ones:

```
ESC=1,  TAB=15, ENTER=28, BACKSPACE=14, SPACE=57,
LEFTCTRL=29, LEFTSHIFT=42, LEFTALT=56, LEFTMETA=125,
RIGHTCTRL=97, RIGHTSHIFT=54, RIGHTALT=100, RIGHTMETA=126,
Q=16 W=17 E=18 R=19 T=20 Y=21 U=22 I=23 O=24 P=25
A=30 S=31 D=32 F=33 G=34 H=35 J=36 K=37 L=38
Z=44 X=45 C=46 V=47 B=48 N=49 M=50
1=2  2=3  3=4  4=5  5=6  6=7  7=8  8=9  9=10  0=11
F1=59 F2=60 F3=61 F4=62 F5=63 F6=64 F7=65 F8=66 F9=67 F10=68 F11=87 F12=88
UP=103 DOWN=108 LEFT=105 RIGHT=106
HOME=102 END=107 PAGEUP=104 PAGEDOWN=109 INSERT=110 DELETE=111
CAPSLOCK=58 MINUS=12 EQUAL=13 GRAVE=41 COMMA=51 DOT=52 SLASH=53
SEMICOLON=39 APOSTROPHE=40 LEFTBRACE=26 RIGHTBRACE=27 BACKSLASH=43
```

(Letters/values follow the evdev qwerty mapping; the phone editor uses these
constants, never raw numbers.)

Mouse button IDs (protocol-level, not evdev): `1=left 2=right 3=middle
4=back(X1) 5=forward(X2)`.

The authoritative table lives in code: `pc-protocol/src/keycodes.rs` (Rust) and
`android-app/app/src/main/java/com/phonecontroller/app/net/KeyCodes.kt`
(Kotlin). Both are generated from this document's rules and unit-tested to
agree on the common set.

## 4. Packet kinds

| Kind | Hex | Dir | Transport | Encrypted | Payload |
|---|---|---|---|---|---|
| DISCOVER | 0x01 | phone→broadcast | UDP | no | empty |
| ANNOUNCE | 0x02 | pc→phone | UDP | no | see §5.1 |
| HELLO | 0x03 | phone→pc | TCP | no | see §5.2 |
| HELLO_REPLY | 0x04 | pc→phone | TCP | no | see §5.3 |
| AUTH_PROOF | 0x05 | both | TCP | no | 32-byte HMAC-SHA256 proof (§6.2) |
| SECURE_FRAME | 0x06 | both | TCP | wraps inner | inner control packet (§6.3) |
| PING | 0x07 | both | TCP | yes | 8-byte client timestamp (echoed) |
| PONG | 0x08 | both | TCP | yes | same 8 bytes echoed |
| BYE | 0x09 | both | TCP | yes | u8 reason: 0 normal, 1 timeout, 2 replaced, 3 error |
| RESET | 0x0A | phone→pc | TCP | yes | empty — release all held inputs for session |
| PROFILE_INFO | 0x0B | phone→pc | TCP | yes | u8 name_len + name (UTF-8, ≤64 B) — logging only |
| ERROR | 0x0C | both | any | if SECURE wrapped | u16 code, u8 msg_len, msg |
| INPUT_DELTA | 0x10 | phone→pc | UDP | yes | see §5.5 |
| INPUT_SNAPSHOT | 0x11 | phone→pc | UDP | yes | see §5.6 |
| STATS | 0x12 | pc→phone | UDP | yes | see §5.7 |

ERROR codes: `0x0001 protocol version mismatch`, `0x0002 auth failed (wrong
PIN)`, `0x0003 malformed packet`, `0x0004 server busy (session limit)`,
`0x0005 internal error`, `0x0006 session not established`.

Discovery scope: a client **may** send DISCOVER from every active IPv4
interface, to both `255.255.255.255` and that interface's directed broadcast
address, and must listen on all of those sockets. The receiver replies with a
unicast ANNOUNCE to the source address/port of the DISCOVER datagram. Results
are deduplicated by sender address. This is required when the phone's default
route is mobile data but the PC is attached to the phone's Wi-Fi hotspot; see
[`HOTSPOT.md`](HOTSPOT.md).

## 5. Payload layouts

### 5.1 ANNOUNCE (plaintext UDP reply, sent to source address of DISCOVER)

```
u16  proto_version        (1)
u16  tcp_port             (41000)
u8   name_len (≤32)
[..] name (UTF-8, no NULs)
u8   flags                bit0 = requires_pin (1)
```

### 5.2 HELLO (plaintext TCP, first packet of a session)

```
u16  proto_version
u8   name_len (≤32)
[..] device_name (UTF-8)
u8   reserved (0)
[16] client_nonce (random)
```

### 5.3 HELLO_REPLY (plaintext TCP)

```
u16  proto_version
u32  session_id           (random nonzero; identifies the session everywhere)
u8   name_len (≤32)
[..] receiver_name
u8   flags                bit0 = pin_required
u8   reserved (0)
[16] server_nonce (random)
```

### 5.4 AUTH_PROOF (plaintext TCP, both directions after HELLO exchange)

```
[32] tag = HMAC-SHA256(key, transcript)
```

`key = HKDF-SHA256(ikm = "phonecontroller-v1" ‖ 0x00 ‖ PIN ‖ client_nonce ‖
server_nonce, salt = none, info = b"pc session key", L = 32)`

`transcript = client_nonce ‖ server_nonce ‖ u32le(session_id) ‖ 0x00 (client→pc)
/ 0x01 (pc→client)`. HMAC is keyed with `key`. Either side failing the proof
sends ERROR 0x0002 and closes.

### 5.5 INPUT_DELTA (encrypted UDP, phone→pc)

```
u32  session_id
u16  event_count (≤32; datagram limit rules out more)
event[event_count]:
    u8   op
    u16  code
    i32  a
    i32  b
u32  probe_ts             phone-local ms (mod 2^32); echoed in STATS
```

Event ops (`op`):

| op | Meaning | code | a | b |
|---|---|---|---|---|
| 0 | KEY_DOWN | evdev u16 | — | — |
| 1 | KEY_UP | evdev u16 | — | — |
| 2 | MOUSE_DOWN | button id 1–5 | — | — |
| 3 | MOUSE_UP | button id 1–5 | — | — |
| 4 | MOUSE_REL | 0 | **dx Q16.16** | **dy Q16.16** |
| 5 | SCROLL | 0=vertical 1=horizontal | ticks i32 | — |

`MOUSE_REL` a/b are **Q16.16 fixed point** (65536 = 1 device pixel). The
receiver keeps the sub-pixel remainder and emits integer evdev REL events; the
phone performs all sensitivity/accel/smoothing so slow gyro aim does not
truncate to zero.

**Gyro aiming is not a wire concept either.** All sensor ingestion, quaternion
math, calibration, deadzone/smoothing and sensitivity scaling happen on the
phone; gyro output is ordinary `MOUSE_REL` Q16.16 data summed with touchpad
deltas in the same accumulator. The receiver cannot tell which source produced a
delta, and no sensor samples or gyro configuration are transmitted.

**Macros are not a wire concept.** The phone validates and expands a macro into
the same `KEY_DOWN`/`KEY_UP` events as touch controls (with reverse-order
releases) and sends them through the normal ordered UDP FIFO. The receiver has
no macro interpreter and cannot be asked to run stored automation.

### 5.6 INPUT_SNAPSHOT (encrypted UDP, phone→pc, every 1 s or after resync suspicion)

```
u32  session_id
u32  base_seq             seq of the last INPUT_DELTA the phone considers applied
u8   mouse_bits           bit0..4 = buttons 1..5 held
u16  key_count
u16  keycodes[key_count]  sorted ascending (evdev codes currently held)
```

The receiver diffs against its own held set and reconciles (press missing, release
extra). Snapshots make loss self-healing without acknowledgements.

### 5.7 STATS (encrypted UDP, pc→phone, ≤4 Hz while a session has traffic)

```
u32  last_seq_processed
u32  events_per_sec
u32  packets_dropped_total   (malformed/decrypt failures, per session)
u32  rtt_probe_echo          latest probe_ts observed (legacy input-based echo)
```

`rtt_probe_echo` only advances when an INPUT_DELTA arrives, so it becomes a
stale timestamp as soon as the user pauses. The reference Android client
therefore measures live latency with TCP PING/PONG instead; the field is kept
in the wire format for compatibility and diagnostics.

## 6. Crypto framing

### 6.1 Keys

- One AES-256-GCM key per session (both directions).
- `session_id` is 4 random nonzero bytes generated by the PC and carried in
  HELLO_REPLY.

### 6.2 Nonce and sequence rules

- Nonce (12 B) = `direction(1 B)` ‖ `counter(7 B)` where counter is the
  packet's `seq` field (zero-padded, little-endian). direction: `0x00` =
  phone→pc, `0x01` = pc→phone.
- `seq` starts at 1 and increments by 1 per encrypted packet **per direction**.
  TCP SECURE_FRAME seq and UDP input seq are independent streams.
- Both sides reject a *backwards* seq (replay) and count it; a *gap* is
  tolerated for UDP (loss) but recorded in STATS.
- AAD (additional authenticated data) = the full 10-byte packet header.
- 128-bit GCM tag is appended to the ciphertext (on-wire payload = ciphertext
  + 16-byte tag).

### 6.3 SECURE_FRAME (TCP transport for encrypted control packets)

```
header.seq            SECURE_FRAME stream counter (1,2,3,…)
payload (ciphertext): the inner control packet (PING…ERROR), framed with the
                      same header layout (inner header is part of the
                      plaintext and included in AAD? -> NO: only outer header
                      is AAD; inner header is ordinary payload bytes).
```

### 6.4 Handshake sequence

```
phone                        pc
  │ DISCOVER (UDP, per-interface
  │           broadcast)       │
  │◀───────────── ANNOUNCE ────│
  │──── TCP connect ──────────▶│
  │──── HELLO ────────────────▶│
  │◀──── HELLO_REPLY ──────────│
  │──── AUTH_PROOF ───────────▶│   (both derive key, verify tag)
  │◀──── AUTH_PROOF ───────────│
  │        SECURE state        │
  │──── SECURE{PING} ─────────▶│ every 2 s, both sides
  │◀─── SECURE{PONG} ──────────│
```

Wrong PIN ⇒ ERROR 0x0002, close. No input may flow before SECURE.

## 7. Session lifecycle & input safety

- **Established:** after both AUTH_PROOFs verify. Receiver registers
  `session_id → session` and begins accepting UDP input for it.
- **Heartbeat:** either side may PING every 2 s; TCP idle timeout 10 s;
  UDP input silence timeout default 5 s (receiver watchdog) ⇒ release all
  held inputs, drop session, close TCP. The phone's PING/PONG exchange is
  also the live RTT measurement, and its periodic TCP traffic keeps the
  receiver's idle timeout satisfied while no inputs are held.
- **BYE:** graceful; receiver releases all held inputs *then* removes session.
- **RESET:** phone requests full release (profile switch, app background).
- **Replacement:** receiver default max 1 input session; a new successful
  pairing releases the old session's inputs first, then BYE(reason 2).
- **Disconnect (TCP FIN/RST):** release all held inputs immediately; UDP-only
  sessions cannot exist (input requires an established TCP session).
- **Snapshot reconciliation** is a correctness mechanism, never a release
  trigger by itself.

## 8. Worked example (INPUT_DELTA)

Phone holds W (17) and left mouse, moving right slowly (dx = +1.5 px, dy = 0):

```
plaintext payload (LE):
  9a 3b 00 00                 session_id = 0x00003B9A
  03 00                       event_count = 3
  00 11 00 00 00 00 00 00 00  KEY_DOWN code=17(0x11)
  02 01 00 00 00 00 00 00 00  MOUSE_DOWN btn=1
  04 00 80 5e 01 00 00 00 00  MOUSE_REL a=0x00015E80 (=98112/65536=1.497px)
  e5 1c 00 00                 probe_ts
→ encrypted with key, nonce=00‖seq(7B), header seq=42, kind=0x10
```
