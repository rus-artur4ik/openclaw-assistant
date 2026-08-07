# OpenClaw Talk mode (server-side voice engine)

The voice overlay can run on one of two engines. This document describes the second one.

| | On device (default) | OpenClaw Talk |
|---|---|---|
| Speech recognition | Android `SpeechRecognizer` | openclaw gateway (server STT) |
| Agent | app sends text to the backend | openclaw runs the agent |
| Voice | local TTS (Android / ElevenLabs / OpenAI / VOICEVOX) | openclaw's Talk TTS |
| Needs a gateway | no | yes |
| Microphone | opened per turn | open for the whole conversation (full duplex) |

Pick the engine in **Settings → Voice → Conversation engine**. It applies to the voice overlay —
long-press Home, the wake word, and the Bluetooth media button. The in-app chat microphone always
uses the on-device engine.

## What actually happens on the wire

OpenClaw Talk is not "the server does everything and the phone is a speaker". The gateway runs
recognition and synthesis, but it does **not** run the agent by itself: it asks the client to, and
waits. One turn looks like this:

```
app  → talk.session.create {mode:"realtime", transport:"gateway-relay",
                            brain:"agent-consult", sessionKey}
                                   → {relaySessionId, audio:{pcm16, 24000Hz}, expiresAt}
app  → talk.session.appendAudio {sessionId, audioBase64, timestamp}   (~25/s while listening)
     ← talk.event {type:"transcript", role:"user", final:true}
     ← talk.event {type:"toolCall", name:"openclaw_agent_consult", forced:true, callId}
app  → talk.session.submitToolResult {callId, {status:"working"}, options:{willContinue:true}}
                                   (this is what makes the gateway speak a filler phrase)
app  → talk.client.toolCall {sessionKey, callId, name, args, relaySessionId} → {runId}
     ← chat {runId, state:"final", message}                            (a normal chat run)
app  → talk.session.submitToolResult {callId, {result: text}}
     ← talk.event {type:"audio"} × N  →  talk.event {type:"audioDone"}
app  → talk.session.close {sessionId}
```

Implementation: [`TalkRelayController`](../app/src/main/java/com/openclaw/assistant/talk/TalkRelayController.kt),
with the wire model in [`TalkRelayProtocol`](../app/src/main/java/com/openclaw/assistant/talk/TalkRelayProtocol.kt)
and the RPCs in [`TalkRelayClient`](../app/src/main/java/com/openclaw/assistant/talk/TalkRelayClient.kt).

Because the consult is a normal `chat.send` into `sessionKey`, the voice exchange lands in the same
gateway session the app already shows — the same behaviour the existing gateway voice path has.

## openclaw settings this mode obeys

Everything below lives in openclaw's own config, not in the app. Settings → Voice shows a read-only
copy fetched with `talk.config` (never with `includeSecrets`, so no API key reaches the device).

| openclaw setting | Effect |
|---|---|
| `talk.provider`, `talk.providers.<id>.voiceId` / `modelId` / `languageCode` | The voice you hear |
| `talk.agentId` | Which agent answers, when the session key does not pin one |
| `talk.consultThinkingLevel`, `talk.consultFastMode` | Thinking level / fast mode of the consult run |
| `talk.realtime.mode`, `talk.realtime.transport` | Session shape requested from the gateway |
| STT provider (auto-selected from configured plugins) | Recognition quality **and** whether barge-in works — barge-in comes from the STT provider's server VAD |
| `OPENCLAW_TALK_FILLER_PHRASES` | The "one moment" phrases played while the agent works |

`talk.interruptOnSpeech` is deliberately **not** shown: this deployment's STT-TTS cascade never reads
it.

## App settings that stop applying

Shown dimmed with "managed by openclaw" rather than hidden, so it stays obvious what changed:

- Read answers aloud, TTS provider / engine / voice / speed (Settings → Voice)
- Filler phrases (Settings → Voice)
- Continuous conversation, barge-in, silence timeout (Settings → Chat)
- Speech language (Settings → Language)

Wake word, sensitivity and the activation sound keep working — they are local triggers, and the
engine only decides what happens after activation.

## Behaviour worth knowing

**Fallback.** The engine is resolved once per voice session against the actual routing and gateway
health ([`VoiceEngineSelector`](../app/src/main/java/com/openclaw/assistant/backend/VoiceEngineSelector.kt)).
If OpenClaw Talk is selected but unusable — Hermes-routed wake word, no gateway backend, gateway
offline, `talk.session.create` fails — the turn runs on device and the overlay says why. Once the
relay has produced a transcript or audio, a failure is reported as an error instead: finishing
someone's sentence in a different voice is worse than stopping.

**Wake word.** The relay holds the microphone continuously, so Vosk is paused for the whole
conversation and re-paused every two minutes (its own watchdog re-arms after five). Interrupting is
done by speaking — the server VAD hears it — or by tapping the sphere.

**Tap to interrupt.** `talk.session.cancelOutput` only emits `clear` on this deployment and does not
cancel the running synthesis, so the app also drops incoming audio locally until the next utterance.

**Echo cancellation.** The mic stays open while the answer plays, so the capture session requests
hardware AEC and playback runs in `MODE_IN_COMMUNICATION`. Without AEC the server VAD hears the
assistant and interrupts itself. `TalkRelayController.echoCancellationEnabled` reports what the
device actually gave us.

**Data.** The uplink is uncompressed PCM16 at 24 kHz, base64 over JSON-RPC — roughly 4 MB per minute
of conversation. Settings says so under the switch.

**Session limits.** The gateway allows 2 relay sessions per connection and 64 globally, with a fixed
30-minute TTL that `ttlMs` cannot change. The app closes its session on every teardown path; note
that a gateway reconnect orphans the old session until the TTL expires, because only the connection
that created it may close it.

**Telemetry.** `voicetraces.report` is not sent in this mode — the gateway already derives the stage
durations for the same turn, and reporting from the app would double-count it.

## Notes for a different gateway

The wire protocol above is the same one openclaw's own Control UI speaks, so the app works against
both the STT-TTS cascade and a provider-native realtime session. Only the create response
distinguishes them: `voiceSessionId` is present for the provider-native path and absent for the
cascade. `mode` always echoes the request and `provider` is the STT provider id in both cases, so
neither can be used for this.
