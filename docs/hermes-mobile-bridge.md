# WakeClaw Mobile Bridge for Hermes

The **Mobile Bridge** is an optional, opt-in HTTP service exposed by the
WakeClaw for Android app. It lets Hermes Agent (or any other authenticated
caller) invoke a curated set of on-device Android capabilities — listing
installed apps, reading the clipboard, getting device info, etc.

Hermes itself is just an LLM agent server; it has no direct access to the
phone. The Mobile Bridge is what closes that gap, in a way the user is
always in control of.

## Security model in one paragraph

The bridge is **disabled by default**. The user must flip it on inside
WakeClaw settings. When enabled, the app generates a 256-bit random
**bridge token** that every request (except `/health`) must send as
`Authorization: Bearer <token>`. By default the server binds to
`127.0.0.1`, so only software running on the phone (an adb-forwarded
connection, a Tailscale tunnel, etc.) can reach it. Medium- and
high-risk capabilities require an explicit local approval unless the
user has flipped the bridge into "trusted" mode. The token is stored
in `EncryptedSharedPreferences` and is **never written to logs**.

## Enabling the bridge

1. Open WakeClaw → **Settings → Mobile Bridge**.
2. Toggle **Enable Mobile Bridge**.
3. Pick a **bind mode**:
   - **Local only** (default, recommended) — phone software only.
   - **LAN / VPN** — exposed on `0.0.0.0`; protect with Tailscale or a
     trusted Wi-Fi. The UI shows a strong warning.
4. Tap **Generate token** and copy it.
5. Open the **Capability allowlist** and enable the groups you want
   Hermes to access (`device`, `apps`, `clipboard.read`, …).
6. The screen shows the **device URL** (e.g. `http://<device-ip>:8787`)
   that Hermes should call.

## Endpoints

All endpoints are JSON. `/health` is unauthenticated; everything else
requires `Authorization: Bearer <token>`.

| Method | Path                  | Description                                  |
|--------|-----------------------|----------------------------------------------|
| GET    | `/health`             | Liveness probe (unauthenticated)             |
| POST   | `/pair`               | Redeem a 6-char pairing code for the token (unauthenticated) |
| GET    | `/manifest`           | List enabled capabilities                    |
| POST   | `/execute`            | Run a capability                             |
| POST   | `/cancel/{requestId}` | Best-effort cancel of a pending action       |
| GET    | `/grants`             | List per-capability TTL grants               |
| POST   | `/revoke`             | Revoke a single grant or all grants          |

### Pairing flow (Hermes-Relay style)

1. The user opens **Settings → Mobile Bridge → Open pairing screen**. The
   app generates a one-shot 6-character code (Base32 minus `I`/`O`/`L`/`1`)
   and a deep-link payload `agentvoice://pair?u=<bridge-url>&c=<code>&n=<nonce>&e=<expiresMs>`.
2. The desktop CLI either scans the payload off the screen or the user
   types the 6-character code into `hermes-pair`.
3. The CLI POSTs `{"code": "ABC234"}` to `/pair`. The Bridge atomically
   consumes the offer and returns `{"token": "…"}`. Replay is impossible
   (one-shot; offers expire after 5 minutes).

### Grants & destructive verbs

Approve a capability and the UI offers four lifetimes: **once**, **10 min**,
**1 hour**, or **until revoked**. Granted capabilities skip the prompt for
their lifetime. A capability whose name matches a destructive verb
(`delete`, `wipe`, `send`, `transfer`, `format`, `shutdown`, …) ignores
the grant and always prompts. `GET /grants` returns the live list; any
client can issue `POST /revoke {"capability":"apps.launch"}` or
`POST /revoke {"all":"true"}` to drop them.

### Multi-endpoint racing

A Hermes backend entry carries a primary URL plus optional **LAN**,
**Tailscale**, and **public** URLs. On every connection the
`HermesEndpointRacer` probes them all in parallel via `GET /v1/models`
(with `/health` fallback) and uses whichever responds first. The
winning URL is cached for the lifetime of the process and refreshed on
the next test/connect — so the same paired backend works at home, on
the train, or via Tailscale without reconfiguration.

### Accessibility Bridge

Sideload-only capability group `accessibility` (gated by
`BuildConfig.IS_SIDELOAD`). The user must enable the WakeClaw
Accessibility service in **Settings → Accessibility** before any of
`screen.tap`, `screen.swipe`, `screen.home`, `screen.back`, or
`screen.window.describe` appear in `/manifest`. The service has no
auto-behaviour: it acts only when the bridge dispatches a capability
to it.

## curl examples

```bash
export BRIDGE=http://192.168.1.42:8787
export TOKEN=...     # from the WakeClaw UI

# Liveness
curl -s $BRIDGE/health

# What can I do?
curl -s -H "Authorization: Bearer $TOKEN" $BRIDGE/manifest | jq .

# Get device info
curl -s -H "Authorization: Bearer $TOKEN" \
     -H "Content-Type: application/json" \
     -d '{"requestId":"r1","capability":"device.info","arguments":{}}' \
     $BRIDGE/execute | jq .
```

## Using from Hermes

The companion Python helper at
[`../integrations/hermes-mobile-bridge`](../integrations/hermes-mobile-bridge)
wraps these endpoints so a Hermes tool can invoke them by name. Point
Hermes at it with two environment variables:

```bash
export AGENT_VOICE_BRIDGE_URL=http://192.168.1.42:8787
export AGENT_VOICE_BRIDGE_TOKEN=...   # never commit this
```

Then ask Hermes things like *"list the apps installed on my phone"* or
*"what's currently on my clipboard"*. The helper validates the token,
calls the bridge, and surfaces structured errors back to the model.

## Network-exposure warning

Binding the bridge to anything other than `127.0.0.1` makes the
device reachable from the network. Even though every request is bearer
-authenticated and destructive actions require approval, a leaked token
would still let an attacker enumerate apps, read your clipboard, and
attempt approvals. **Prefer Tailscale, a WireGuard tunnel, or
`adb forward tcp:8787 tcp:8787` over a raw LAN bind.** Rotate the
token if you suspect it has been exposed.
