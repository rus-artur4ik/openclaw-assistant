# Connecting WakeClaw to Hermes

There is no host-side helper any more — the app states the steps itself, under
**Settings → Connection Settings → Hermes Agent → Add Hermes Agent**. For
reference, they are:

```bash
hermes config set platforms.api_server.enabled true
```

Then in `~/.hermes/.env`:

```
API_SERVER_HOST=0.0.0.0
API_SERVER_KEY=pick-a-long-random-value
```

```bash
hermes gateway restart
```

If no service is installed yet, run this once — `gateway run` is the
foreground mode, and closing that terminal drops the phone:

```bash
hermes gateway install --start-now --start-on-login
```

The API server platform is off on a fresh install, and its bind address
defaults to `127.0.0.1`, which a phone cannot reach — those two facts are why
both of the first steps are needed. Afterwards the app can find the server by
walking the local subnet, or you can type the address in.

OpenClaw is paired separately, with the setup code from `openclaw qr`.

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
