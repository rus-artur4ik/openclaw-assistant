# WakeClaw pairing helper

The fastest way to set up WakeClaw is to install the host-side helper, then
scan the one QR it prints:

```bash
curl -fsSL https://raw.githubusercontent.com/yuga-hashimoto/openclaw-assistant/main/integrations/agentvoice-pair/install.sh | bash
agentvoice-pair
```

The helper checks whether Hermes, OpenClaw, and Tailscale are installed locally,
asks which backends to include, and creates one WakeClaw setup QR. If the QR
is too large for the current terminal, it opens a generated SVG QR image instead
of printing an unreadable terminal block. Scanning that single QR in WakeClaw
can configure both Hermes and OpenClaw.
If you want the phone to work outside your LAN, answer yes when it asks about
Tailscale/VPN endpoint candidates.

---

# hermes-mobile-bridge

Python helper for calling the **WakeClaw for Android** Mobile Bridge
from a Hermes Agent toolchain.

The bridge itself is documented at
[`docs/hermes-mobile-bridge.md`](../../docs/hermes-mobile-bridge.md).
This package gives Hermes (or any Python tool) a clean SDK over its
`/health`, `/manifest`, `/execute`, and `/cancel/{id}` endpoints.

## Install

Drop `mobile_bridge.py` into your Hermes tool directory, or
`pip install requests` and import it directly.

## Configure

The helper reads two environment variables:

| Variable                    | Description                                |
|-----------------------------|--------------------------------------------|
| `AGENT_VOICE_BRIDGE_URL`    | e.g. `http://192.168.1.42:8787`            |
| `AGENT_VOICE_BRIDGE_TOKEN`  | The token shown in WakeClaw Settings       |

Never commit the token; it grants execute access to opt-in capabilities.

## Examples

```python
from mobile_bridge import MobileBridge

bridge = MobileBridge()  # picks up env vars
print(bridge.health())
print(bridge.get_manifest())
print(bridge.execute("device.info"))
```

```python
# Launch an app — assumes the user has approved apps.launch in
# WakeClaw and is on hand to confirm the medium-risk prompt.
bridge.execute("apps.launch", {"packageName": "com.android.settings"})
```

## Error handling

Every helper raises `MobileBridgeError` on transport failures or
non-2xx responses. `execute` additionally raises if the bridge
returned `status: "failed"`, surfacing the original `code` and
`message` so the LLM can see exactly what went wrong (for example
`unsupported_capability` or `approval_required`).
