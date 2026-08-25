"""Python helper for the WakeClaw for Android Mobile Bridge.

Designed to be dropped into a Hermes Agent tool directory. Reads
`AGENT_VOICE_BRIDGE_URL` and `AGENT_VOICE_BRIDGE_TOKEN` from the
environment, exposes `health`, `get_manifest`, `execute`, and `cancel`
methods, and raises `MobileBridgeError` on any failure so Hermes can
report a meaningful message back to the model.
"""
from __future__ import annotations

import os
import uuid
from dataclasses import dataclass
from typing import Any, Mapping, Optional

import requests


class MobileBridgeError(RuntimeError):
    """Raised on transport errors, non-2xx responses, or `status: failed`."""

    def __init__(self, message: str, *, code: Optional[str] = None, http_status: Optional[int] = None):
        super().__init__(message)
        self.code = code
        self.http_status = http_status


@dataclass(frozen=True)
class MobileBridgeConfig:
    base_url: str
    token: str
    timeout: float = 15.0

    @classmethod
    def from_env(cls) -> "MobileBridgeConfig":
        base = os.environ.get("AGENT_VOICE_BRIDGE_URL", "").rstrip("/")
        token = os.environ.get("AGENT_VOICE_BRIDGE_TOKEN", "")
        if not base:
            raise MobileBridgeError("AGENT_VOICE_BRIDGE_URL is not set")
        if not token:
            raise MobileBridgeError("AGENT_VOICE_BRIDGE_TOKEN is not set")
        return cls(base_url=base, token=token)


class MobileBridge:
    """Thin client over the Mobile Bridge HTTP API."""

    def __init__(self, config: Optional[MobileBridgeConfig] = None, session: Optional[requests.Session] = None) -> None:
        self._config = config or MobileBridgeConfig.from_env()
        self._session = session or requests.Session()

    # ----- public API --------------------------------------------------

    def health(self) -> Mapping[str, Any]:
        return self._get("/health", auth=False)

    def get_manifest(self) -> Mapping[str, Any]:
        return self._get("/manifest")

    def execute(self, capability: str, arguments: Optional[Mapping[str, Any]] = None,
                request_id: Optional[str] = None) -> Mapping[str, Any]:
        payload = {
            "requestId": request_id or str(uuid.uuid4()),
            "capability": capability,
            "arguments": dict(arguments or {}),
        }
        envelope = self._post("/execute", payload)
        status = envelope.get("status")
        if status == "completed":
            return envelope.get("result") or {}
        err = envelope.get("error") or {}
        raise MobileBridgeError(
            err.get("message") or f"Mobile bridge returned status={status!r}",
            code=err.get("code"),
        )

    def cancel(self, request_id: str) -> Mapping[str, Any]:
        return self._post(f"/cancel/{request_id}", {})

    # ----- internals ---------------------------------------------------

    def _headers(self, auth: bool) -> dict:
        headers = {"Accept": "application/json"}
        if auth:
            headers["Authorization"] = f"Bearer {self._config.token}"
        return headers

    def _get(self, path: str, *, auth: bool = True) -> Mapping[str, Any]:
        return self._request("GET", path, auth=auth)

    def _post(self, path: str, payload: Mapping[str, Any]) -> Mapping[str, Any]:
        return self._request("POST", path, json=payload, auth=True)

    def _request(self, method: str, path: str, *, auth: bool, json: Optional[Mapping[str, Any]] = None) -> Mapping[str, Any]:
        url = f"{self._config.base_url}{path}"
        try:
            resp = self._session.request(method, url, headers=self._headers(auth), json=json, timeout=self._config.timeout)
        except requests.RequestException as exc:
            raise MobileBridgeError(f"Mobile bridge transport error: {exc}") from exc
        if resp.status_code >= 400:
            raise MobileBridgeError(
                f"Mobile bridge HTTP {resp.status_code}: {resp.text[:200]}",
                http_status=resp.status_code,
            )
        try:
            return resp.json()
        except ValueError as exc:
            raise MobileBridgeError("Mobile bridge returned non-JSON response") from exc
