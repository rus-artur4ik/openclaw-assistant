---
name: agent-voice-self-setup
description: Diagnose and repair a WakeClaw install end-to-end. Use when the user reports that paired devices stopped working, when bridge auto-disabled, or when "is everything wired correctly?" — equivalent to running `hermes-status` plus walking through the WakeClaw setup wizard.
---

# WakeClaw - self-setup / status skill

Use this skill when:

- The user says something stopped working after pairing.
- A previous tool call hit `unauthorized`, `unsupported_capability`, or
  network errors against the Mobile Bridge.
- The user explicitly asks for a health check / status report.

## What to do

1. **Read the manifest** (`GET /manifest` on the WakeClaw Mobile Bridge).
   If it 401s, the token rotated → ask the user to re-pair via
   the key: compare API_SERVER_KEY in `~/.hermes/.env` with what the app
   has stored for that backend.
2. **Cross-check expected capabilities**. The minimum a healthy install
   should advertise is `device.info`, `apps.list`, and `clipboard.read`. If
   any of those are missing despite Mobile Bridge being on, prompt the
   user to open **Mobile Bridge settings → Capability allowlist** and
   re-tick the relevant groups.
3. **If the user reports that wake word / Voice Overlay still routes to
   OpenClaw** even though Hermes is "Primary": ask them to open
   **Home → Manage backends** and confirm a single backend is marked
   Primary with `enabled = true`. The dispatcher only routes when both
   are true.
4. **Bridge auto-disabled itself.** That means the Idle watchdog elapsed.
   Tell the user it's by design — they can raise `autoDisableIdleMs` in
   Mobile Bridge settings, or set it to 0 to disable.
5. **Pin mismatch on HTTPS endpoint.** TOFU stored a pin for that host
   on first connect; a cert rotation will trip it. The user can clear
   the pin from Backends → edit Hermes → "Reset TOFU pin" (advanced).

## When NOT to use this skill

- The user's question is about modifying a Hermes prompt or model, not
  the WakeClaw connection.
- They explicitly want to factory-reset; use a teardown skill instead.

## What to report back

Always end with a structured snapshot:

```
Bridge:    on/off, port, auto-disable=Xm
Primary:   <name> @ <winning-url> (latencyMs)
Notif:     enabled/disabled
A11y:      enabled/disabled
Build:     sideload | play
Pinned:    [list of TOFU-pinned hosts]
```
