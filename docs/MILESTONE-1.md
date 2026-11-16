# Milestone 1 report — Architecture and repository setup

**Status: complete.**

## What was implemented

- Repository/environment inspection and technical risk review (Wayland input
  permissions, uinput, SendInput limits, gyro feasibility).
- Target architecture with documented decisions (evdev key space, event deltas
  + snapshots, phone-side sensitivity/macros, AES-GCM session crypto,
  platform-isolated backends, input-safety guarantees).
- Normative protocol v1 (`docs/PROTOCOL.md`): header, packet kinds, payload
  layouts, discovery/pairing, crypto framing, replay and sequence rules.
- Rust workspace with five crates:
  - `pc-protocol` — framing, handshake, input payloads, crypto, keycodes.
  - `input-core` — platform-free `InputBackend` trait and held-state machine
    (dedupe, snapshot reconciliation, release-all).
  - `input-linux` — raw `/dev/uinput` backend via `libc` ioctls, no root.
  - `input-windows` — `SendInput` backend with scan-code injection.
  - `pc-receiver` — server and simulator binaries.
- Linux and Windows setup documentation, README, milestone checklist.
- Cross-language compatibility vectors printed by a Rust example and designed
  to be pinned by the Kotlin tests.

## Files created or modified

```text
ARCHITECTURE.md
README.md
docs/PROTOCOL.md
docs/PLAN.md
docs/linux-setup.md
docs/windows-setup.md
pc-receiver/Cargo.toml
pc-receiver/Cargo.lock
pc-receiver/crates/pc-protocol/{Cargo.toml,src/*.rs,examples/compat_vectors.rs}
pc-receiver/crates/input-core/{Cargo.toml,src/*.rs}
pc-receiver/crates/input-linux/{Cargo.toml,src/*.rs,examples/node_probe.rs}
pc-receiver/crates/input-windows/{Cargo.toml,src/lib.rs}
pc-receiver/crates/pc-receiver/{Cargo.toml,src/**,tests/integration.rs}
```

## Build and test results

Date: 2026-11-14, CachyOS, Rust 1.92.0.

```text
cargo test --workspace --no-fail-fast
  13 input-core
   5 input-linux (real uinput readback; skipped if /dev/uinput unavailable)
   4 input-windows (scan-code table, compile-gated)
  22 pc-protocol
  12 pc-receiver integration
  = 56 passed, 0 failed

cargo clippy --workspace --all-targets -- -D warnings
  = 0 warnings
```

The Linux backend readback tests create a real uinput device and verify
`EV_KEY`/`EV_REL` events through an evdev reader. `/dev/uinput` was writable by
the test user; no root was required.

## Known limitations

- Windows backend runtime validation requires a Windows 10/11 machine (not
  available); compilation and scan-code mapping are verified, runtime is not.
- Physical Android device validation was not possible in Milestone 1 (no app
  yet); addressed as far as possible in Milestone 2.
- The original checklist had incorrectly pre-marked later milestones as done;
  it was corrected in `docs/PLAN.md`.

## Remaining tasks

- Milestone 2 onwards (communication, touchpad/mouse, grid editor, profiles,
  macros, gyro, cross-platform validation).

## Exact commands

```bash
cd pc-receiver
cargo test --workspace --no-fail-fast
cargo clippy --workspace --all-targets -- -D warnings
cargo run -p pc-protocol --example compat_vectors
cargo run -p input-linux --example node_probe   # optional: inspect the node
```
