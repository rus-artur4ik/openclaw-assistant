#!/usr/bin/env python3
"""agentvoice-pair / hermes-pair — print one QR for WakeClaw setup.

The helper is intentionally host-side and mostly automatic:

1. Detect whether Hermes, OpenClaw, and Tailscale are installed locally.
2. Read common env vars, .env files, and config JSON files before asking.
3. Include detected Hermes/OpenClaw/Tailscale candidates automatically.
4. Print one WakeClaw setup JSON QR. Scanning that single QR in WakeClaw
   can configure both Hermes Agent and OpenClaw. A deep-link fallback is also
   printed for external camera apps.

It keeps the older Hermes-only CLI options for scripted use.
"""
from __future__ import annotations

import argparse
import base64
import getpass
import ipaddress
import json
import os
import re
import shutil
import secrets
import signal
import socket
import struct
import subprocess
import sys
import tempfile
import time
import urllib.parse
import urllib.request
import zlib
from pathlib import Path
from typing import Any, Iterable, List, Optional


def truthy(value: str) -> bool:
    return value.strip().lower() in {"1", "true", "yes", "y", "on"}


def ask_yes_no(prompt: str, default: bool) -> bool:
    suffix = "Y/n" if default else "y/N"
    raw = input(f"{prompt} [{suffix}] ").strip()
    if not raw:
        return default
    return truthy(raw)


def run(cmd: List[str], timeout: int = 8) -> Optional[str]:
    try:
        out = subprocess.run(cmd, check=True, capture_output=True, text=True, timeout=timeout)
        return out.stdout.strip()
    except Exception:
        return None


def is_port_open(host: str, port: int, timeout: float = 0.25) -> bool:
    try:
        with socket.create_connection((host, port), timeout=timeout):
            return True
    except Exception:
        return False


def hermes_models_endpoint_ok(base_url: str, api_key: Optional[str], timeout: float = 4.0) -> bool:
    url = normalize_url(base_url).rstrip("/") + "/v1/models"
    req = urllib.request.Request(url)
    if api_key:
        req.add_header("Authorization", f"Bearer {api_key}")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as response:
            return 200 <= response.status < 300
    except Exception:
        return False


def http_get_ok(url: str, timeout: float = 4.0) -> bool:
    try:
        with urllib.request.urlopen(urllib.request.Request(url), timeout=timeout) as response:
            return 200 <= response.status < 300
    except Exception:
        return False


def first_lan_ip() -> Optional[str]:
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
            sock.connect(("8.8.8.8", 80))
            return sock.getsockname()[0]
    except Exception:
        return None


def tailscale_cmd() -> Optional[List[str]]:
    cli = shutil.which("tailscale")
    if cli:
        return [cli]
    mac_app_cli = Path("/Applications/Tailscale.app/Contents/MacOS/Tailscale")
    if mac_app_cli.exists() and os.access(mac_app_cli, os.X_OK):
        return [str(mac_app_cli)]
    return None


def tailscale_ip() -> Optional[str]:
    cmd = tailscale_cmd()
    if not cmd:
        return None
    out = run([*cmd, "ip", "-4"])
    if not out:
        return None
    for line in out.splitlines():
        candidate = line.strip()
        try:
            ipaddress.ip_address(candidate)
        except ValueError:
            continue
        return candidate
    return None


def tailscale_dns_name() -> Optional[str]:
    cmd = tailscale_cmd()
    if not cmd:
        return None
    out = run([*cmd, "status", "--json"])
    if not out:
        return None
    try:
        name = str(json.loads(out).get("Self", {}).get("DNSName", "")).strip().rstrip(".")
    except Exception:
        return None
    return name or None


def upsert_env_file(path: Path, key: str, value: str) -> None:
    lines = path.read_text().splitlines() if path.exists() else []
    prefix = f"{key}="
    replaced = False
    out = []
    for line in lines:
        if line.startswith(prefix):
            out.append(f"{key}={value}")
            replaced = True
        else:
            out.append(line)
    if not replaced:
        out.append(f"{key}={value}")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("\n".join(out).rstrip() + "\n")


def set_hermes_api_server_host(host: str) -> bool:
    path = Path.home() / ".hermes" / "config.yaml"
    if not path.exists():
        return False
    text = path.read_text()
    updated = re.sub(r"(?m)^(\s+host:\s*)127\.0\.0\.1\s*$", rf"\g<1>{host}", text, count=1)
    if updated == text:
        return False
    path.write_text(updated)
    return True


def restart_hermes_gateway() -> None:
    subprocess.run(["pkill", "-f", "hermes_cli.main gateway run"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    subprocess.Popen(
        ["hermes", "gateway", "run", "--replace"],
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
        stdin=subprocess.DEVNULL,
        start_new_session=True,
    )


def wait_for_hermes_remote(remote_host: str, api_key: Optional[str], timeout_seconds: float = 20.0) -> bool:
    deadline = time.monotonic() + timeout_seconds
    base_url = f"http://{remote_host}:8642"
    while time.monotonic() < deadline:
        if is_port_open(remote_host, 8642, timeout=1.0) and hermes_models_endpoint_ok(base_url, api_key, timeout=3.0):
            return True
        time.sleep(1.0)
    return False


def wait_for_hermes_url(base_url: str, api_key: Optional[str], timeout_seconds: float = 35.0) -> bool:
    deadline = time.monotonic() + timeout_seconds
    while time.monotonic() < deadline:
        if hermes_models_endpoint_ok(base_url, api_key, timeout=5.0):
            return True
        time.sleep(1.0)
    return False


def wait_for_http_url(url: str, timeout_seconds: float = 35.0) -> bool:
    deadline = time.monotonic() + timeout_seconds
    while time.monotonic() < deadline:
        if http_get_ok(url, timeout=5.0):
            return True
        time.sleep(1.0)
    return False


def discover_dashboard_terminal(local_url: str = "http://127.0.0.1:9119") -> Optional[dict]:
    """Return Hermes dashboard terminal pairing data when `dashboard --tui` is running."""
    try:
        with urllib.request.urlopen(local_url.rstrip("/") + "/", timeout=2.0) as response:
            html = response.read().decode("utf-8", errors="replace")
    except Exception:
        return None
    token_match = re.search(r'__HERMES_SESSION_TOKEN__="([^"]+)"', html)
    tui_match = re.search(r"__HERMES_DASHBOARD_EMBEDDED_CHAT__=(true|false)", html)
    if not token_match:
        return None
    if tui_match and tui_match.group(1) != "true":
        print("Hermes dashboard is running, but Terminal is disabled. Start it with `hermes dashboard --tui`.", file=sys.stderr)
        return None
    return {"url": normalize_url(local_url), "token": token_match.group(1)}


def dashboard_terminal_url(no_tailscale: bool, no_lan: bool, port: int = 9119) -> str:
    if not no_tailscale:
        for host in [tailscale_dns_name(), tailscale_ip()]:
            if host:
                return f"http://{host}:{port}"
    if not no_lan:
        lan = first_lan_ip()
        if lan:
            return f"http://{lan}:{port}"
    return f"http://127.0.0.1:{port}"


def start_cloudflared_tunnel(local_url: str, label: str, timeout_seconds: float = 35.0) -> Optional[str]:
    cloudflared = shutil.which("cloudflared")
    if not cloudflared:
        return None
    log_path = Path(tempfile.gettempdir()) / f"agentvoice-{label}-cloudflared.log"
    log_file = log_path.open("w+")
    proc = subprocess.Popen(
        [cloudflared, "tunnel", "--url", local_url, "--no-autoupdate"],
        stdout=log_file,
        stderr=subprocess.STDOUT,
        stdin=subprocess.DEVNULL,
        start_new_session=True,
    )
    deadline = time.monotonic() + timeout_seconds
    pattern = re.compile(r"https://[A-Za-z0-9-]+\.trycloudflare\.com")
    url = None
    while time.monotonic() < deadline and proc.poll() is None:
        log_file.flush()
        try:
            text = log_path.read_text(errors="ignore")
        except Exception:
            text = ""
        match = pattern.search(text)
        if match:
            url = match.group(0)
            break
        time.sleep(0.5)
    log_file.close()
    if not url:
        proc.terminate()
        return None
    pid_path = Path(tempfile.gettempdir()) / f"agentvoice-{label}-cloudflared.pid"
    pid_path.write_text(str(proc.pid))
    print(f"Started temporary public tunnel for {label}: {url}")
    print(f"  Stop later: kill {proc.pid}")
    return url


def start_ngrok_tunnel(local_url: str, label: str, timeout_seconds: float = 35.0) -> Optional[str]:
    ngrok = shutil.which("ngrok")
    if not ngrok:
        return None
    existing = existing_ngrok_tunnel(local_url)
    if existing:
        print(f"Reusing existing public ngrok tunnel for {label}: {existing}")
        return existing
    log_path = Path(tempfile.gettempdir()) / f"agentvoice-{label}-ngrok.log"
    log_file = log_path.open("w+")
    proc = subprocess.Popen(
        [ngrok, "http", local_url, "--log", "stdout", "--log-format", "json"],
        stdout=log_file,
        stderr=subprocess.STDOUT,
        stdin=subprocess.DEVNULL,
        start_new_session=True,
    )
    deadline = time.monotonic() + timeout_seconds
    pattern = re.compile(r"https://[A-Za-z0-9.-]+\.ngrok[^\"\\\s]*")
    url = None
    while time.monotonic() < deadline and proc.poll() is None:
        log_file.flush()
        try:
            text = log_path.read_text(errors="ignore")
        except Exception:
            text = ""
        match = pattern.search(text)
        if match:
            url = match.group(0)
            break
        time.sleep(0.5)
    log_file.close()
    if not url:
        proc.terminate()
        return None
    pid_path = Path(tempfile.gettempdir()) / f"agentvoice-{label}-ngrok.pid"
    pid_path.write_text(str(proc.pid))
    print(f"Started temporary public ngrok tunnel for {label}: {url}")
    print(f"  Stop later: kill {proc.pid}")
    return url


def existing_ngrok_tunnel(local_url: str) -> Optional[str]:
    expected = normalize_url(local_url)
    for port in range(4040, 4050):
        try:
            with urllib.request.urlopen(f"http://127.0.0.1:{port}/api/tunnels", timeout=0.75) as response:
                if not (200 <= response.status < 300):
                    continue
                data = json.loads(response.read().decode("utf-8"))
        except Exception:
            continue
        for tunnel in data.get("tunnels", []):
            public_url = str(tunnel.get("public_url") or "")
            config = tunnel.get("config") if isinstance(tunnel.get("config"), dict) else {}
            addr = normalize_url(str(config.get("addr") or ""))
            if public_url.startswith("https://") and addr == expected:
                return public_url.rstrip("/")
    return None


def start_public_tunnel(local_url: str, label: str, provider: str = "auto") -> Optional[str]:
    providers = [provider]
    if provider == "auto":
        providers = ["ngrok", "cloudflare"]
    for item in providers:
        if item == "ngrok":
            url = start_ngrok_tunnel(local_url, label)
        elif item == "cloudflare":
            url = start_cloudflared_tunnel(local_url, label)
        else:
            raise SystemExit(f"Unknown tunnel provider: {provider}")
        if url:
            return url
    return None


MUX_HERMES_PREFIX = "/__agentvoice_hermes_api"
MUX_DASHBOARD_PREFIX = "/__agentvoice_hermes_dashboard"
MUX_TERMINAL_PREFIX = "/__agentvoice_terminal"


def stop_temp_process(label: str, provider: str) -> None:
    pid_path = Path(tempfile.gettempdir()) / f"agentvoice-{label}-{provider}.pid"
    try:
        pid = int(pid_path.read_text().strip())
    except Exception:
        return
    try:
        os.kill(pid, signal.SIGTERM)
    except ProcessLookupError:
        pass
    except Exception:
        return
    try:
        pid_path.unlink()
    except Exception:
        pass


def find_free_local_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.bind(("127.0.0.1", 0))
        return int(sock.getsockname()[1])


def start_multiplex_proxy(port: int, approval_port: Optional[int] = None) -> Optional[str]:
    node = shutil.which("node")
    if not node:
        return None
    script_path = Path(tempfile.gettempdir()) / "agentvoice-public-mux.js"
    terminal_route = ""
    if approval_port:
        terminal_route = f"  {{ prefix: '{MUX_TERMINAL_PREFIX}', host: '127.0.0.1', port: {approval_port}, rewriteHost: true }},\n"
    script_path.write_text(
        (
        r"""
const http = require('http');
const net = require('net');

const port = Number(process.argv[2]);
const routes = [
  { prefix: '/__agentvoice_hermes_api', host: '127.0.0.1', port: 8642, rewriteHost: true },
  { prefix: '/__agentvoice_hermes_dashboard', host: '127.0.0.1', port: 9119, rewriteHost: true },
"""
        + terminal_route +
        r"""
];
const fallback = { host: '127.0.0.1', port: 18789, rewriteHost: false };

function routeFor(url) {
  for (const route of routes) {
    if (url === route.prefix || url.startsWith(route.prefix + '/')) {
      const stripped = url.slice(route.prefix.length) || '/';
      return { ...route, path: stripped };
    }
  }
  return { ...fallback, path: url || '/' };
}

function sanitizeUpgradeHeaders(headers, target) {
  const result = { ...headers };
  if (target.port === 9119) {
    delete result['forwarded'];
    delete result['x-forwarded-for'];
    delete result['x-real-ip'];
  }
  return result;
}

const server = http.createServer((req, res) => {
  const target = routeFor(req.url);
  const headers = { ...req.headers };
  if (target.rewriteHost) headers.host = `${target.host}:${target.port}`;
  const proxy = http.request(
    { host: target.host, port: target.port, method: req.method, path: target.path, headers },
    (upstream) => {
      res.writeHead(upstream.statusCode || 502, upstream.headers);
      upstream.pipe(res);
    },
  );
  proxy.on('error', (err) => {
    res.writeHead(502, { 'content-type': 'text/plain; charset=utf-8' });
    res.end(`agentvoice mux upstream error: ${err.message}`);
  });
  req.pipe(proxy);
});

server.on('upgrade', (req, socket, head) => {
  const target = routeFor(req.url);
  const upstream = net.connect(target.port, target.host, () => {
    const headers = sanitizeUpgradeHeaders(req.headers, target);
    if (target.rewriteHost) headers.host = `${target.host}:${target.port}`;
    const lines = [`${req.method} ${target.path} HTTP/${req.httpVersion}`];
    for (const [key, value] of Object.entries(headers)) {
      if (Array.isArray(value)) {
        for (const item of value) lines.push(`${key}: ${item}`);
      } else if (value !== undefined) {
        lines.push(`${key}: ${value}`);
      }
    }
    upstream.write(lines.join('\r\n') + '\r\n\r\n');
    if (head && head.length) upstream.write(head);
    upstream.pipe(socket);
    socket.pipe(upstream);
  });
  upstream.on('error', () => socket.destroy());
});

server.listen(port, '127.0.0.1');
""".strip()
        )
        + "\n"
    )
    log_path = Path(tempfile.gettempdir()) / "agentvoice-public-mux.log"
    log_file = log_path.open("w+")
    proc = subprocess.Popen(
        [node, str(script_path), str(port)],
        stdout=log_file,
        stderr=subprocess.STDOUT,
        stdin=subprocess.DEVNULL,
        start_new_session=True,
    )
    deadline = time.monotonic() + 5.0
    while time.monotonic() < deadline:
        if proc.poll() is not None:
            log_file.close()
            return None
        if is_port_open("127.0.0.1", port, timeout=0.2):
            log_file.close()
            (Path(tempfile.gettempdir()) / "agentvoice-public-mux.pid").write_text(str(proc.pid))
            return f"http://127.0.0.1:{port}"
        time.sleep(0.1)
    proc.terminate()
    log_file.close()
    return None


def start_multiplexed_public_tunnel(provider: str, approval_port: Optional[int] = None) -> Optional[str]:
    if provider not in ("auto", "ngrok"):
        return None
    stop_temp_process("mux", "ngrok")
    stop_temp_process("openclaw", "ngrok")
    try:
        pid = int((Path(tempfile.gettempdir()) / "agentvoice-public-mux.pid").read_text().strip())
        os.kill(pid, signal.SIGTERM)
    except Exception:
        pass
    port = find_free_local_port()
    local_url = start_multiplex_proxy(port, approval_port=approval_port)
    if not local_url:
        return None
    public_url = start_ngrok_tunnel(local_url, "mux")
    if not public_url:
        try:
            pid = int((Path(tempfile.gettempdir()) / "agentvoice-public-mux.pid").read_text().strip())
            os.kill(pid, signal.SIGTERM)
        except Exception:
            pass
        return None
    return public_url.rstrip("/")


def start_terminal_command_server() -> Optional[dict]:
    secret = secrets.token_urlsafe(24)
    port = find_free_local_port()
    script_path = Path(tempfile.gettempdir()) / f"agentvoice-terminal-{port}.py"
    script_path.write_text(
        r'''
import json
import os
import re
import subprocess
import sys
import time
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

port = int(sys.argv[1])
secret = sys.argv[2]

def run_command(command, timeout):
    return subprocess.run(
        command,
        shell=True,
        cwd=str(Path.home()),
        capture_output=True,
        text=True,
        timeout=timeout,
        executable=os.environ.get("SHELL") or "/bin/zsh",
    )

def resolve_openclaw_latest_approval(command, proc, timeout):
    if command != "openclaw devices approve --latest" or proc.returncode == 0:
        return proc
    combined = (proc.stdout or "") + "\n" + (proc.stderr or "")
    match = re.search(r"openclaw\s+devices\s+approve\s+([0-9a-fA-F-]{36})(?:\s+--json)?", combined)
    if not match:
        return proc
    return run_command(f"openclaw devices approve {match.group(1)}", timeout)

def approve_latest_openclaw(timeout):
    last_error = None
    approved_stdout = ""
    approved_stderr = ""
    attempts = max(1, min(int(timeout * 2), 120))
    for _ in range(attempts):
        list_proc = run_command("openclaw devices list --json", timeout)
        if list_proc.returncode != 0:
            return list_proc
        try:
            payload = json.loads(list_proc.stdout or "{}")
            pending = payload.get("pending") or []
            if not pending:
                last_error = "No pending OpenClaw pairing request.\n"
                time.sleep(0.5)
                continue
            operator_pending = [
                item for item in pending
                if item.get("role") == "operator"
                or "operator" in (item.get("roles") or [])
                or any(str(scope).startswith("operator.") for scope in (item.get("scopes") or []))
            ]
            if not operator_pending:
                selected = max(pending, key=lambda item: int(item.get("ts") or 0))
                request_id = str(selected.get("requestId") or "").strip()
                if not request_id:
                    last_error = "Latest OpenClaw pairing request has no requestId.\n"
                    time.sleep(0.5)
                    continue
                approve_proc = run_command(f"openclaw devices approve {request_id}", timeout)
                if approve_proc.returncode == 0:
                    approved_stdout += approve_proc.stdout or ""
                    approved_stderr += approve_proc.stderr or ""
                    last_error = "Approved a preliminary OpenClaw node request; waiting for operator request.\n"
                    time.sleep(0.5)
                    continue
                if "unknown requestId" in ((approve_proc.stderr or "") + (approve_proc.stdout or "")):
                    last_error = approve_proc.stderr or approve_proc.stdout or "OpenClaw pairing request disappeared before approval.\n"
                    time.sleep(0.4)
                    continue
                return approve_proc
            selected = max(operator_pending, key=lambda item: int(item.get("ts") or 0))
            request_id = str(selected.get("requestId") or "").strip()
        except Exception as exc:
            return subprocess.CompletedProcess(
                args="openclaw devices approve --latest",
                returncode=1,
                stdout=list_proc.stdout,
                stderr=f"Could not parse OpenClaw pending device list: {exc}\n",
            )
        if not request_id:
            return subprocess.CompletedProcess(
                args="openclaw devices approve --latest",
                returncode=1,
                stdout=list_proc.stdout,
                stderr="Latest OpenClaw pairing request has no requestId.\n",
            )
        approve_proc = run_command(f"openclaw devices approve {request_id}", timeout)
        if approve_proc.returncode == 0:
            return subprocess.CompletedProcess(
                args="openclaw devices approve --latest",
                returncode=0,
                stdout=approved_stdout + (approve_proc.stdout or ""),
                stderr=approved_stderr + (approve_proc.stderr or ""),
            )
        if "unknown requestId" not in ((approve_proc.stderr or "") + (approve_proc.stdout or "")):
            return approve_proc
        last_error = approve_proc.stderr or approve_proc.stdout or "OpenClaw pairing request disappeared before approval.\n"
        time.sleep(0.4)
    return subprocess.CompletedProcess(
        args="openclaw devices approve --latest",
        returncode=1,
        stdout="",
        stderr=last_error or "No pending OpenClaw operator pairing request.\n",
    )

class TerminalHandler(BaseHTTPRequestHandler):
    server_version = "AgentVoiceTerminal/1"

    def log_message(self, fmt, *args):
        return

    def _json(self, status, payload):
        body = json.dumps(payload, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("content-type", "application/json; charset=utf-8")
        self.send_header("cache-control", "no-store")
        self.send_header("content-length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):
        parsed = urllib.parse.urlparse(self.path)
        if parsed.path != "/run":
            self._json(404, {"ok": False, "error": "not_found"})
            return
        length = int(self.headers.get("content-length", "0") or "0")
        if length > 32768:
            self._json(413, {"ok": False, "error": "too_large"})
            return
        try:
            body = json.loads(self.rfile.read(length).decode("utf-8") or "{}")
        except Exception:
            self._json(400, {"ok": False, "error": "invalid_json"})
            return
        if str(body.get("secret") or "") != secret:
            self._json(403, {"ok": False, "error": "forbidden"})
            return
        command = str(body.get("command") or "").strip()
        if not command:
            self._json(400, {"ok": False, "error": "missing_command"})
            return
        timeout = int(body.get("timeoutSeconds") or 30)
        timeout = max(1, min(timeout, 120))
        try:
            if command == "openclaw devices approve --latest":
                proc = approve_latest_openclaw(timeout)
            else:
                proc = run_command(command, timeout)
                proc = resolve_openclaw_latest_approval(command, proc, timeout)
        except subprocess.TimeoutExpired:
            self._json(504, {"ok": False, "error": "timeout"})
            return
        self._json(200, {
            "ok": proc.returncode == 0,
            "exitCode": proc.returncode,
            "stdout": proc.stdout[-8000:],
            "stderr": proc.stderr[-8000:],
        })

ThreadingHTTPServer(("127.0.0.1", port), TerminalHandler).serve_forever()
'''.lstrip()
    )
    stop_temp_process("terminal", "server")
    log_path = Path(tempfile.gettempdir()) / "agentvoice-terminal-server.log"
    log_file = log_path.open("w+")
    try:
        proc = subprocess.Popen(
            [sys.executable, str(script_path), str(port), secret],
            stdout=log_file,
            stderr=subprocess.STDOUT,
            stdin=subprocess.DEVNULL,
            start_new_session=True,
        )
    except Exception:
        log_file.close()
        return None
    deadline = time.monotonic() + 5.0
    while time.monotonic() < deadline:
        if proc.poll() is not None:
            log_file.close()
            return None
        if is_port_open("127.0.0.1", port, timeout=0.2):
            log_file.close()
            (Path(tempfile.gettempdir()) / "agentvoice-terminal-server.pid").write_text(str(proc.pid))
            break
        time.sleep(0.1)
    else:
        proc.terminate()
        log_file.close()
        return None
    return {"port": port, "secret": secret}


def origin_variants_for_url(url: str) -> List[str]:
    parsed = urllib.parse.urlparse(url)
    scheme = parsed.scheme.lower()
    if scheme not in ("http", "https") or not parsed.hostname:
        return []
    default_port = 443 if scheme == "https" else 80
    port = parsed.port or default_port
    host = parsed.hostname
    if ":" in host and not host.startswith("["):
        host = f"[{host}]"
    default_origin = f"{scheme}://{host}" if port == default_port else f"{scheme}://{host}:{port}"
    origins = [default_origin]
    explicit_origin = f"{scheme}://{host}:{port}"
    if explicit_origin not in origins:
        origins.append(explicit_origin)
    return origins


def configure_openclaw_allowed_origin(public_url: str) -> bool:
    openclaw = shutil.which("openclaw")
    if not openclaw:
        return False
    origins_to_add = origin_variants_for_url(public_url)
    if not origins_to_add:
        return False
    current_raw = run([openclaw, "config", "get", "gateway.controlUi.allowedOrigins"], timeout=10)
    try:
        current = json.loads(current_raw or "[]")
    except Exception:
        current = []
    if not isinstance(current, list):
        current = []
    merged = [str(item) for item in current if str(item).strip()]
    changed = False
    for origin in origins_to_add:
        if origin not in merged:
            merged.append(origin)
            changed = True
    if not changed:
        return True
    patch = json.dumps({"gateway": {"controlUi": {"allowedOrigins": merged}}}, ensure_ascii=False)
    try:
        subprocess.run(
            [openclaw, "config", "patch", "--stdin"],
            input=patch,
            text=True,
            capture_output=True,
            check=True,
            timeout=45,
        )
    except Exception as err:
        print(f"Warning: could not add OpenClaw allowed origin for public tunnel: {err}", file=sys.stderr)
        return False
    restarted = subprocess.run(
        [openclaw, "gateway", "restart", "--force", "--wait", "15s"],
        capture_output=True,
        text=True,
        timeout=25,
    )
    if restarted.returncode != 0:
        print("Warning: OpenClaw allowed origin was updated, but Gateway restart failed. Run `openclaw gateway restart --force`.", file=sys.stderr)
        return False
    print(f"Configured OpenClaw Gateway allowed origin for public tunnel: {origins_to_add[0]}")
    return True


def maybe_configure_hermes_remote_access(hermes_key: Optional[str], remote_host: Optional[str]) -> Optional[str]:
    if not remote_host or not sys.stdin.isatty():
        return hermes_key
    remote_url = f"http://{remote_host}:8642"
    if is_port_open(remote_host, 8642, timeout=0.5) and hermes_models_endpoint_ok(remote_url, hermes_key, timeout=2.0):
        return hermes_key
    if not is_port_open("127.0.0.1", 8642):
        return hermes_key
    print("Hermes API Server is reachable only from this Mac right now.")
    print(f"To use WakeClaw from your phone, Hermes must listen on Tailscale/LAN: http://{remote_host}:8642")
    if not ask_yes_no("Configure Hermes API Server for phone access and restart it now?", True):
        return hermes_key
    env_path = Path.home() / ".hermes" / ".env"
    key = hermes_key or secrets.token_urlsafe(32)
    upsert_env_file(env_path, "API_SERVER_KEY", key)
    upsert_env_file(env_path, "HERMES_API_SERVER_URL", f"http://{remote_host}:8642")
    set_hermes_api_server_host("0.0.0.0")
    restart_hermes_gateway()
    print("Configured Hermes API Server host=0.0.0.0, saved API_SERVER_KEY, and restarted Hermes Gateway.")
    if wait_for_hermes_remote(remote_host, key):
        print(f"Verified Hermes API Server from the phone-reachable URL: {remote_url}")
    else:
        print(
            f"Warning: Hermes was configured, but {remote_url} did not pass /v1/models yet. "
            "Check firewall/VPN and run agentvoice-pair again.",
            file=sys.stderr,
        )
    return key


def read_json(path: Path) -> dict:
    try:
        return json.loads(path.read_text())
    except Exception:
        return {}


def unquote_env_value(value: str) -> str:
    value = value.strip()
    if len(value) >= 2 and value[0] == value[-1] and value[0] in ("'", '"'):
        return value[1:-1]
    return value


def read_env_file(path: Path) -> dict:
    try:
        lines = path.read_text().splitlines()
    except Exception:
        return {}
    result = {}
    for line in lines:
        stripped = line.strip()
        if not stripped or stripped.startswith("#") or "=" not in stripped:
            continue
        if stripped.startswith("export "):
            stripped = stripped[len("export "):].lstrip()
        key, value = stripped.split("=", 1)
        key = key.strip()
        if re.match(r"^[A-Za-z_][A-Za-z0-9_]*$", key):
            result[key] = unquote_env_value(value.split(" #", 1)[0])
    return result


def iter_config_values(value: Any) -> Iterable[tuple[str, Any]]:
    if isinstance(value, dict):
        for key, child in value.items():
            yield str(key), child
            yield from iter_config_values(child)
    elif isinstance(value, list):
        for child in value:
            yield from iter_config_values(child)


def unique_append(values: List[str], value: Optional[str]) -> None:
    if value and value not in values:
        values.append(value)


def readable_existing(paths: Iterable[Path]) -> List[Path]:
    result = []
    for path in paths:
        try:
            if path.expanduser().is_file():
                result.append(path.expanduser())
        except Exception:
            pass
    return result


HERMES_JSON_PATHS = (
    Path.home() / ".config" / "hermes" / "config.json",
    Path.home() / ".config" / "hermes" / "settings.json",
    Path.home() / ".hermes" / "config.json",
    Path.home() / ".hermes" / "settings.json",
    Path.home() / "Library" / "Application Support" / "Hermes" / "config.json",
    Path.cwd() / "hermes.json",
)

HERMES_ENV_PATHS = (
    Path.home() / ".config" / "hermes" / ".env",
    Path.home() / ".hermes" / ".env",
    Path.home() / ".hermes" / "env",
    Path.cwd() / ".env",
    Path.cwd() / ".env.local",
    Path.cwd() / ".env.hermes",
)

OPENCLAW_JSON_PATHS = (
    Path.home() / ".config" / "openclaw" / "config.json",
    Path.home() / ".config" / "openclaw" / "settings.json",
    Path.home() / ".openclaw" / "openclaw.json",
    Path.home() / ".openclaw" / "config.json",
    Path.home() / ".openclaw" / "settings.json",
    Path.home() / ".openclaw" / "gateway.json",
    Path.cwd() / "openclaw.json",
)

OPENCLAW_ENV_PATHS = (
    Path.home() / ".config" / "openclaw" / ".env",
    Path.home() / ".openclaw" / ".env",
    Path.cwd() / ".env",
    Path.cwd() / ".env.local",
    Path.cwd() / ".env.openclaw",
)


def env_lookup(keys: Iterable[str], envs: Iterable[dict] = ()) -> Optional[str]:
    for key in keys:
        if os.environ.get(key):
            return os.environ[key].strip()
    for env in envs:
        for key in keys:
            value = str(env.get(key, "")).strip()
            if value:
                return value
    return None


def json_lookup(paths: Iterable[Path], keys: set[str]) -> Optional[str]:
    lowered = {key.lower() for key in keys}
    for path in readable_existing(paths):
        cfg = read_json(path)
        for key, value in iter_config_values(cfg):
            if key.lower() in lowered:
                text = str(value).strip()
                if text:
                    return text
    return None


def discover_hermes_url() -> Optional[str]:
    envs = [read_env_file(path) for path in readable_existing(HERMES_ENV_PATHS)]
    value = env_lookup(
        ("HERMES_API_SERVER_URL", "HERMES_URL", "AGENT_VOICE_HERMES_URL", "HERMES_BASE_URL"),
        envs,
    )
    if value:
        return value
    value = json_lookup(
        HERMES_JSON_PATHS,
        {"apiServerUrl", "api_server_url", "url", "baseUrl", "base_url", "serverUrl", "server_url"},
    )
    if value and value.startswith(("http://", "https://")):
        return value
    return None


def discover_hermes_key() -> Optional[str]:
    envs = [read_env_file(path) for path in readable_existing(HERMES_ENV_PATHS)]
    return env_lookup(
        ("HERMES_API_KEY", "HERMES_API_SERVER_KEY", "API_SERVER_KEY", "AGENT_VOICE_HERMES_KEY"),
        envs,
    ) or json_lookup(
        HERMES_JSON_PATHS,
        {"apiKey", "api_key", "apiServerKey", "api_server_key", "key", "token", "bearerToken"},
    )


def discover_hermes_model() -> Optional[str]:
    envs = [read_env_file(path) for path in readable_existing(HERMES_ENV_PATHS)]
    return env_lookup(("HERMES_MODEL", "HERMES_MODEL_NAME", "AGENT_VOICE_HERMES_MODEL"), envs) or json_lookup(
        HERMES_JSON_PATHS,
        {"model", "modelName", "model_name", "defaultModel", "default_model"},
    )


def discover_hermes_runs_api() -> Optional[bool]:
    envs = [read_env_file(path) for path in readable_existing(HERMES_ENV_PATHS)]
    value = env_lookup(("HERMES_USE_RUNS_API", "HERMES_RUNS_API", "AGENT_VOICE_HERMES_RUNS"), envs) or json_lookup(
        HERMES_JSON_PATHS,
        {"useRunsApi", "use_runs_api", "runsApi", "runs_api"},
    )
    if value is None:
        return None
    return truthy(value)


def discover_hermes_streaming() -> Optional[bool]:
    envs = [read_env_file(path) for path in readable_existing(HERMES_ENV_PATHS)]
    value = env_lookup(("HERMES_STREAMING", "HERMES_USE_STREAMING", "AGENT_VOICE_HERMES_STREAMING"), envs) or json_lookup(
        HERMES_JSON_PATHS,
        {"streaming", "useStreaming", "use_streaming"},
    )
    if value is None:
        return None
    return truthy(value)


def discover_openclaw_setup_code() -> Optional[str]:
    envs = [read_env_file(path) for path in readable_existing(OPENCLAW_ENV_PATHS)]
    value = env_lookup(("OPENCLAW_SETUP_CODE", "AGENT_VOICE_OPENCLAW_SETUP_CODE"), envs) or json_lookup(
        OPENCLAW_JSON_PATHS,
        {"setupCode", "setup_code", "gatewaySetupCode", "gateway_setup_code", "pairingCode", "pairing_code"},
    )
    return value.strip() if value else None


def normalize_url(raw: str) -> str:
    raw = raw.strip()
    if not raw:
        return raw
    if not raw.startswith(("http://", "https://")):
        raw = "http://" + raw
    return raw.rstrip("/")


def is_loopback_url(raw: str) -> bool:
    parsed = urllib.parse.urlparse(normalize_url(raw))
    host = (parsed.hostname or "").lower()
    return host in {"localhost", "::1"} or host.startswith("127.")


def append_host_variant(urls: List[str], host: Optional[str], port: int) -> None:
    if not host:
        return
    candidate = f"http://{host}:{port}"
    if candidate not in urls:
        urls.append(candidate)


def ordered_android_urls(candidates: Iterable[str]) -> List[str]:
    unique = comma_urls(candidates)
    remote = [url for url in unique if not is_loopback_url(url)]
    local = [url for url in unique if is_loopback_url(url)]
    # Android scans this QR on the phone. Loopback URLs point at the phone
    # itself, not at the desktop running Hermes, so keep them only when there
    # is no LAN/Tailscale/public candidate at all.
    return remote if remote else local


def gateway_url_from_openclaw_config(cfg: dict) -> Optional[str]:
    gateway = cfg.get("gateway") if isinstance(cfg.get("gateway"), dict) else {}
    for key in ("publicUrl", "public_url", "url"):
        value = str(gateway.get(key) or cfg.get(key) or "").strip()
        if value.startswith(("http://", "https://", "ws://", "wss://")):
            return value.replace("ws://", "http://").replace("wss://", "https://")
    remote = gateway.get("remote") if isinstance(gateway.get("remote"), dict) else {}
    value = str(remote.get("url") or "").strip()
    if value.startswith(("http://", "https://", "ws://", "wss://")):
        return value.replace("ws://", "http://").replace("wss://", "https://")
    control = gateway.get("controlUi") if isinstance(gateway.get("controlUi"), dict) else {}
    origins = control.get("allowedOrigins") if isinstance(control.get("allowedOrigins"), list) else []
    ts_origin = next((str(origin) for origin in origins if ".ts.net" in str(origin) and str(origin).startswith("https://")), None)
    if ts_origin:
        return ts_origin.rstrip("/")
    port = int(gateway.get("port") or 18789)
    ts_ip = tailscale_ip()
    if ts_ip:
        return f"http://{ts_ip}:{port}"
    lan = first_lan_ip()
    if lan:
        return f"http://{lan}:{port}"
    return f"http://127.0.0.1:{port}"


def encode_setup_code(payload: dict) -> str:
    raw = json.dumps(payload, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
    return base64.urlsafe_b64encode(raw).decode("ascii").rstrip("=")


def decode_setup_code(code: str) -> Optional[dict]:
    try:
        return json.loads(base64.urlsafe_b64decode(code + "=" * ((4 - len(code) % 4) % 4)).decode("utf-8"))
    except Exception:
        return None


def setup_code_with_url(code: str, url: str) -> str:
    payload = decode_setup_code(code) or {}
    payload["url"] = url.rstrip("/")
    return encode_setup_code(payload)


def setup_code_for_host_approval(code: Optional[str], approval: Optional[dict]) -> Optional[str]:
    if not code or not approval or not approval.get("url") or not approval.get("secret"):
        return code
    payload = decode_setup_code(code)
    if not payload or not payload.get("password"):
        return code
    payload.pop("bootstrapToken", None)
    return encode_setup_code(payload)


def openclaw_setup_code_from_config() -> Optional[str]:
    for path in readable_existing(OPENCLAW_JSON_PATHS):
        cfg = read_json(path)
        gateway = cfg.get("gateway") if isinstance(cfg.get("gateway"), dict) else {}
        auth = gateway.get("auth") if isinstance(gateway.get("auth"), dict) else {}
        url = gateway_url_from_openclaw_config(cfg)
        if not url:
            continue
        payload: dict = {"url": url}
        mode = str(auth.get("mode") or "").lower()
        token = str(auth.get("token") or "").strip()
        password = str(auth.get("password") or "").strip()
        if mode == "password" and password:
            payload["password"] = password
        elif token:
            payload["token"] = token
        elif password:
            payload["password"] = password
        else:
            continue
        return encode_setup_code(payload)
    return None


def openclaw_setup_code_from_cli() -> Optional[str]:
    if not shutil.which("openclaw"):
        return None
    for cmd in (["openclaw", "qr", "--setup-code-only"], ["openclaw", "qr", "--json"]):
        out = run(cmd)
        if not out:
            continue
        try:
            obj = json.loads(out)
            code = str(obj.get("setupCode", "")).strip()
            if code:
                return code
        except Exception:
            pass
        # `--setup-code-only` is expected to print the raw base64url payload.
        first = out.splitlines()[0].strip()
        if first and " " not in first and "{" not in first:
            return first
    return None


def openclaw_setup_code_from_local_install() -> Optional[str]:
    # Prefer local config credentials when available. Current OpenClaw `qr`
    # emits a bootstrapToken for device pairing; WakeClaw also opens an
    # operator session, so include both the local password and bootstrapToken
    # when we can resolve them on the user's own machine.
    config_code = openclaw_setup_code_from_config()
    cli_code = openclaw_setup_code_from_cli()
    if not config_code:
        return cli_code
    if not cli_code:
        return config_code
    config_payload = decode_setup_code(config_code)
    cli_payload = decode_setup_code(cli_code)
    if not config_payload or not cli_payload:
        return config_code
    merged = dict(config_payload)
    if cli_payload.get("bootstrapToken"):
        merged["bootstrapToken"] = cli_payload["bootstrapToken"]
    return encode_setup_code(merged)


def build_pairing_uri(
    hermes_urls: List[str],
    hermes_key: Optional[str],
    model: Optional[str],
    use_runs_api: bool,
    streaming: bool,
    display_name: Optional[str],
    terminal: Optional[dict],
    openclaw_setup_code: Optional[str],
    openclaw_approval: Optional[dict] = None,
) -> str:
    params: List[tuple[str, str]] = []
    for url in hermes_urls:
        if not url.startswith(("http://", "https://")):
            raise SystemExit(f"Hermes URL must start with http:// or https://: {url!r}")
        params.append(("hu", url))
    if hermes_urls:
        if hermes_key:
            params.append(("hk", hermes_key))
        if model:
            params.append(("hm", model))
        params.append(("hr", "1" if use_runs_api else "0"))
        params.append(("hs", "1" if streaming else "0"))
    if hermes_urls and display_name:
        params.append(("hn", display_name))
    if hermes_urls and terminal:
        if terminal.get("url"):
            params.append(("htu", str(terminal["url"])))
        if terminal.get("token"):
            params.append(("htt", str(terminal["token"])))
    if openclaw_setup_code:
        params.append(("oc", openclaw_setup_code))
    if openclaw_approval:
        if openclaw_approval.get("url"):
            params.append(("oau", str(openclaw_approval["url"])))
        if openclaw_approval.get("secret"):
            params.append(("oas", str(openclaw_approval["secret"])))
    if not params:
        raise SystemExit("Nothing to pair. Include Hermes, OpenClaw, or both.")
    return "agentvoice://setup?" + urllib.parse.urlencode(params)


def build_pairing_json(
    hermes_urls: List[str],
    hermes_key: Optional[str],
    model: Optional[str],
    use_runs_api: bool,
    streaming: bool,
    display_name: Optional[str],
    terminal: Optional[dict],
    openclaw_setup_code: Optional[str],
    openclaw_approval: Optional[dict] = None,
) -> str:
    payload: dict = {"type": "agent_voice_setup", "version": 1}
    if hermes_urls:
        hermes: dict = {
            "urls": hermes_urls,
            "model": model or "default",
            "runs": use_runs_api,
            "streaming": streaming,
        }
        if hermes_key:
            hermes["key"] = hermes_key
        if display_name:
            hermes["name"] = display_name
        if terminal and terminal.get("url") and terminal.get("token"):
            hermes["terminal"] = {
                "url": str(terminal["url"]),
                "token": str(terminal["token"]),
            }
        payload["hermes"] = hermes
    if openclaw_setup_code:
        openclaw_payload = {"setupCode": openclaw_setup_code}
        if openclaw_approval and openclaw_approval.get("url") and openclaw_approval.get("secret"):
            openclaw_payload["approval"] = {
                "url": str(openclaw_approval["url"]),
                "secret": str(openclaw_approval["secret"]),
            }
        payload["openclaw"] = openclaw_payload
    if len(payload) <= 2:
        raise SystemExit("Nothing to pair. Include Hermes, OpenClaw, or both.")
    return json.dumps(payload, separators=(",", ":"), ensure_ascii=False)


QR_ECC_CODEWORDS_PER_BLOCK_LOW = [
    -1, 7, 10, 15, 20, 26, 18, 20, 24, 30, 18, 20, 24, 26, 30, 22, 24, 28,
    30, 28, 28, 28, 28, 30, 30, 26, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30,
    30, 30, 30, 30, 30,
]
QR_NUM_ERROR_CORRECTION_BLOCKS_LOW = [
    -1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 4, 4, 4, 4, 4, 6, 6, 6, 6, 7, 8, 8,
    9, 9, 10, 12, 12, 12, 13, 14, 15, 16, 17, 18, 19, 19, 20, 21, 22, 24,
    25,
]


def qr_raw_codewords(version: int) -> int:
    result = (16 * version + 128) * version + 64
    if version >= 2:
        num_align = version // 7 + 2
        result -= (25 * num_align - 10) * num_align - 55
    if version >= 7:
        result -= 36
    return result // 8


def qr_alignment_positions(version: int) -> List[int]:
    if version == 1:
        return []
    num_align = version // 7 + 2
    step = 26 if version == 32 else ((version * 4 + num_align * 2 + 1) // (num_align * 2 - 2)) * 2
    result = [6]
    pos = version * 4 + 10
    for _ in range(num_align - 1):
        result.insert(1, pos)
        pos -= step
    return result


def qr_reed_solomon_multiply(x: int, y: int) -> int:
    z = 0
    for i in range(7, -1, -1):
        z = (z << 1) ^ ((z >> 7) * 0x11D)
        z ^= ((y >> i) & 1) * x
    return z


def qr_reed_solomon_divisor(degree: int) -> List[int]:
    result = [0] * (degree - 1) + [1]
    root = 1
    for _ in range(degree):
        for j in range(degree):
            result[j] = qr_reed_solomon_multiply(result[j], root)
            if j + 1 < degree:
                result[j] ^= result[j + 1]
        root = qr_reed_solomon_multiply(root, 0x02)
    return result


def qr_reed_solomon_remainder(data: List[int], divisor: List[int]) -> List[int]:
    result = [0] * len(divisor)
    for b in data:
        factor = b ^ result.pop(0)
        result.append(0)
        for i, coef in enumerate(divisor):
            result[i] ^= qr_reed_solomon_multiply(coef, factor)
    return result


def qr_append_bits(bits: List[int], value: int, length: int) -> None:
    for i in range(length - 1, -1, -1):
        bits.append((value >> i) & 1)


def qr_encode_codewords(payload: str) -> tuple[int, List[int]]:
    data = payload.encode("utf-8")
    for version in range(1, 41):
        data_capacity_bits = (
            qr_raw_codewords(version)
            - QR_ECC_CODEWORDS_PER_BLOCK_LOW[version] * QR_NUM_ERROR_CORRECTION_BLOCKS_LOW[version]
        ) * 8
        length_bits = 8 if version <= 9 else 16
        bits: List[int] = []
        qr_append_bits(bits, 0x4, 4)
        qr_append_bits(bits, len(data), length_bits)
        for b in data:
            qr_append_bits(bits, b, 8)
        if len(bits) > data_capacity_bits:
            continue
        qr_append_bits(bits, 0, min(4, data_capacity_bits - len(bits)))
        while len(bits) % 8:
            bits.append(0)
        pad_byte = 0xEC
        while len(bits) < data_capacity_bits:
            qr_append_bits(bits, pad_byte, 8)
            pad_byte ^= 0xEC ^ 0x11
        data_codewords = [sum(bits[i + j] << (7 - j) for j in range(8)) for i in range(0, len(bits), 8)]
        return version, qr_add_ecc_and_interleave(version, data_codewords)
    raise SystemExit("Pairing payload is too large for one QR. Reduce endpoints or omit optional tokens.")


def qr_add_ecc_and_interleave(version: int, data: List[int]) -> List[int]:
    num_blocks = QR_NUM_ERROR_CORRECTION_BLOCKS_LOW[version]
    block_ecc_len = QR_ECC_CODEWORDS_PER_BLOCK_LOW[version]
    raw_codewords = qr_raw_codewords(version)
    num_short_blocks = num_blocks - raw_codewords % num_blocks
    short_block_data_len = raw_codewords // num_blocks - block_ecc_len
    divisor = qr_reed_solomon_divisor(block_ecc_len)
    blocks: List[tuple[List[int], List[int]]] = []
    offset = 0
    for i in range(num_blocks):
        data_len = short_block_data_len + (0 if i < num_short_blocks else 1)
        block_data = data[offset: offset + data_len]
        offset += data_len
        blocks.append((block_data, qr_reed_solomon_remainder(block_data, divisor)))
    result: List[int] = []
    max_data_len = max(len(block_data) for block_data, _ in blocks)
    for i in range(max_data_len):
        for block_data, _ in blocks:
            if i < len(block_data):
                result.append(block_data[i])
    for i in range(block_ecc_len):
        for _, block_ecc in blocks:
            result.append(block_ecc[i])
    return result


def qr_mask_bit(mask: int, x: int, y: int) -> bool:
    if mask == 0:
        return (x + y) % 2 == 0
    raise ValueError(mask)


def qr_draw_function_patterns(modules: List[List[Optional[bool]]], is_function: List[List[bool]], version: int) -> None:
    size = len(modules)

    def set_function(x: int, y: int, dark: bool) -> None:
        modules[y][x] = dark
        is_function[y][x] = True

    def draw_finder(cx: int, cy: int) -> None:
        for dy in range(-4, 5):
            for dx in range(-4, 5):
                x = cx + dx
                y = cy + dy
                if 0 <= x < size and 0 <= y < size:
                    dist = max(abs(dx), abs(dy))
                    set_function(x, y, dist != 2 and dist != 4)

    draw_finder(3, 3)
    draw_finder(size - 4, 3)
    draw_finder(3, size - 4)

    align = qr_alignment_positions(version)
    for cy in align:
        for cx in align:
            if is_function[cy][cx]:
                continue
            for dy in range(-2, 3):
                for dx in range(-2, 3):
                    set_function(cx + dx, cy + dy, max(abs(dx), abs(dy)) != 1)

    for i in range(size):
        if not is_function[6][i]:
            set_function(i, 6, i % 2 == 0)
        if not is_function[i][6]:
            set_function(6, i, i % 2 == 0)

    set_function(8, size - 8, True)
    for i in range(9):
        if i != 6:
            set_function(8, i, False)
            set_function(i, 8, False)
    for i in range(8):
        set_function(size - 1 - i, 8, False)
        set_function(8, size - 1 - i, False)

    if version >= 7:
        rem = version
        for _ in range(12):
            rem = (rem << 1) ^ ((rem >> 11) * 0x1F25)
        bits = (version << 12) | rem
        for i in range(18):
            bit = ((bits >> i) & 1) != 0
            a = size - 11 + i % 3
            b = i // 3
            set_function(a, b, bit)
            set_function(b, a, bit)


def qr_draw_format_bits(modules: List[List[Optional[bool]]], is_function: List[List[bool]], mask: int) -> None:
    size = len(modules)
    data = (1 << 3) | mask  # Low error correction.
    rem = data
    for _ in range(10):
        rem = (rem << 1) ^ ((rem >> 9) * 0x537)
    bits = ((data << 10) | rem) ^ 0x5412

    def set_function(x: int, y: int, dark: bool) -> None:
        modules[y][x] = dark
        is_function[y][x] = True

    for i in range(6):
        set_function(8, i, ((bits >> i) & 1) != 0)
    set_function(8, 7, ((bits >> 6) & 1) != 0)
    set_function(8, 8, ((bits >> 7) & 1) != 0)
    set_function(7, 8, ((bits >> 8) & 1) != 0)
    for i in range(9, 15):
        set_function(14 - i, 8, ((bits >> i) & 1) != 0)
    for i in range(8):
        set_function(size - 1 - i, 8, ((bits >> i) & 1) != 0)
    for i in range(8, 15):
        set_function(8, size - 15 + i, ((bits >> i) & 1) != 0)
    set_function(8, size - 8, True)


def qr_make_matrix(payload: str) -> List[List[bool]]:
    version, codewords = qr_encode_codewords(payload)
    size = version * 4 + 17
    modules: List[List[Optional[bool]]] = [[None] * size for _ in range(size)]
    is_function = [[False] * size for _ in range(size)]
    qr_draw_function_patterns(modules, is_function, version)

    bit_index = 0
    upward = True
    x = size - 1
    while x >= 1:
        if x == 6:
            x -= 1
        for vert in range(size):
            y = size - 1 - vert if upward else vert
            for dx in range(2):
                xx = x - dx
                if is_function[y][xx]:
                    continue
                dark = False
                if bit_index < len(codewords) * 8:
                    dark = ((codewords[bit_index >> 3] >> (7 - (bit_index & 7))) & 1) != 0
                    bit_index += 1
                modules[y][xx] = dark ^ qr_mask_bit(0, xx, y)
        upward = not upward
        x -= 2

    qr_draw_format_bits(modules, is_function, 0)
    return [[cell is True for cell in row] for row in modules]


def render_qr_without_dependencies(payload: str, compact: bool = True) -> None:
    modules = qr_make_matrix(payload)
    quiet = 1 if compact else 2
    size = len(modules)
    if compact:
        for y in range(-quiet, size + quiet, 2):
            line = []
            for x in range(-quiet, size + quiet):
                top = 0 <= x < size and 0 <= y < size and modules[y][x]
                bottom_y = y + 1
                bottom = 0 <= x < size and 0 <= bottom_y < size and modules[bottom_y][x]
                line.append("█" if top and bottom else "▀" if top else "▄" if bottom else " ")
            print("".join(line))
        return
    for y in range(-quiet, size + quiet):
        line = []
        for x in range(-quiet, size + quiet):
            dark = 0 <= x < size and 0 <= y < size and modules[y][x]
            line.append("██" if dark else "  ")
        print("".join(line))


def render_qr(payload: str, compact: bool = True) -> bool:
    render_qr_without_dependencies(payload, compact = compact)
    return True


def terminal_qr_size(payload: str, compact: bool = True) -> tuple[int, int]:
    modules = qr_make_matrix(payload)
    quiet = 1 if compact else 2
    size = len(modules) + quiet * 2
    if compact:
        return size, (size + 1) // 2
    return size * 2, size


def write_qr_svg(payload: str, path: Path) -> Path:
    modules = qr_make_matrix(payload)
    quiet = 4
    size = len(modules) + quiet * 2
    cell = 10
    parts = [
        '<?xml version="1.0" encoding="UTF-8"?>',
        f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {size} {size}" width="{size * cell}" height="{size * cell}" shape-rendering="crispEdges">',
        f'<rect width="{size}" height="{size}" fill="#fff"/>',
    ]
    for y, row in enumerate(modules):
        start: Optional[int] = None
        for x, dark in enumerate(row + [False]):
            if dark and start is None:
                start = x
            elif not dark and start is not None:
                parts.append(f'<rect x="{start + quiet}" y="{y + quiet}" width="{x - start}" height="1" fill="#000"/>')
                start = None
    parts.append("</svg>")
    path.write_text("\n".join(parts) + "\n", encoding="utf-8")
    return path


def write_qr_png(payload: str, path: Path) -> Path:
    modules = qr_make_matrix(payload)
    quiet = 4
    scale = 12
    module_count = len(modules) + quiet * 2
    size = module_count * scale
    rows = []
    for py in range(size):
        y = py // scale - quiet
        row = bytearray([0])
        for px in range(size):
            x = px // scale - quiet
            dark = 0 <= x < len(modules) and 0 <= y < len(modules) and modules[y][x]
            row.extend(b"\x00\x00\x00" if dark else b"\xff\xff\xff")
        rows.append(bytes(row))

    def chunk(kind: bytes, data: bytes) -> bytes:
        return (
            struct.pack(">I", len(data))
            + kind
            + data
            + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)
        )

    png = b"".join([
        b"\x89PNG\r\n\x1a\n",
        chunk(b"IHDR", struct.pack(">IIBBBBB", size, size, 8, 2, 0, 0, 0)),
        chunk(b"IDAT", zlib.compress(b"".join(rows), level=9)),
        chunk(b"IEND", b""),
    ])
    path.write_bytes(png)
    return path


def open_file(path: Path) -> bool:
    openers = []
    if sys.platform == "darwin":
        openers.append(["open", str(path)])
    elif os.name == "nt":
        openers.append(["cmd", "/c", "start", "", str(path)])
    else:
        openers.append(["xdg-open", str(path)])
    for cmd in openers:
        try:
            subprocess.Popen(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            return True
        except Exception:
            continue
    return False


def comma_urls(values: Iterable[str]) -> List[str]:
    urls: List[str] = []
    for value in values:
        for part in value.split(","):
            url = normalize_url(part)
            if url and url not in urls:
                urls.append(url)
    return urls


def main(argv: Optional[List[str]] = None) -> int:
    p = argparse.ArgumentParser(prog="agentvoice-pair", description=__doc__.splitlines()[0])
    p.add_argument("--url", action="append", default=[], help="Hermes URL. Repeat or comma-separate for LAN/Tailscale/public.")
    p.add_argument("--key", help="Hermes API key. Defaults to common env/config values, then prompts.")
    p.add_argument("--model", help="Hermes model name. Defaults to detected config, then default.")
    p.add_argument("--runs", action="store_true", help="Use Hermes Runs API mode.")
    p.add_argument("--chat", dest="runs", action="store_false", help="Use Hermes Chat Completions mode.")
    p.add_argument("--no-stream", dest="streaming", action="store_false", help="Disable Hermes streaming responses.")
    p.add_argument("--stream", dest="streaming", action="store_true", help="Enable Hermes streaming responses.")
    p.add_argument("--name", help="Hermes backend display name.")
    p.add_argument("--openclaw-setup-code", help="OpenClaw setup code from `openclaw qr --setup-code-only`.")
    p.add_argument("--hermes-only", action="store_true", help="Only include Hermes.")
    p.add_argument("--openclaw-only", action="store_true", help="Only include OpenClaw.")
    p.add_argument("--interactive", action="store_true", help="Ask before including detected candidates.")
    p.add_argument("--yes", action="store_true", help="Accept detected defaults without prompts. This is the normal behavior.")
    p.add_argument("--no-lan", action="store_true", help="Do not add LAN endpoint candidates automatically.")
    p.add_argument("--no-tailscale", action="store_true", help="Do not add Tailscale endpoint candidates automatically.")
    p.add_argument("--public-tunnel", action="store_true", default=True, help="Start temporary public tunnel URLs for detected local backends. This is the default.")
    p.add_argument("--no-public-tunnel", action="store_true", help="Do not offer temporary public tunnels.")
    p.add_argument(
        "--tunnel-provider",
        choices=("auto", "ngrok", "cloudflare"),
        default="auto",
        help="Public tunnel provider for --public-tunnel. Auto prefers ngrok, then Cloudflare.",
    )
    p.add_argument("--large-qr", action="store_true", help="Render a larger legacy ASCII QR instead of the compact terminal QR.")
    p.add_argument("--terminal-qr", action="store_true", default=True, help="Always print the terminal QR even when it does not fit the current terminal. This is the default.")
    p.add_argument("--no-terminal-qr", action="store_true", help="Skip the terminal QR when it does not fit the current terminal.")
    p.add_argument("--no-open", action="store_true", help="Do not open the generated QR image automatically.")
    p.set_defaults(runs=None, streaming=None)
    args = p.parse_args(argv)

    hermes_installed = bool(shutil.which("hermes"))
    openclaw_installed = bool(shutil.which("openclaw"))
    tailscale_installed = bool(tailscale_cmd())
    hermes_port_open = is_port_open("127.0.0.1", 8642)
    dashboard_port_open = is_port_open("127.0.0.1", 9119)
    openclaw_port_open = is_port_open("127.0.0.1", 18789)
    hermes_key = args.key or discover_hermes_key()
    hermes_model = args.model or discover_hermes_model() or "default"
    discovered_runs = discover_hermes_runs_api()
    hermes_runs = args.runs if args.runs is not None else (True if discovered_runs is None else discovered_runs)
    discovered_streaming = discover_hermes_streaming()
    hermes_streaming = args.streaming if args.streaming is not None else (
        True if discovered_streaming is None else discovered_streaming
    )
    discovered_hermes_url = discover_hermes_url()
    discovered_openclaw_setup_code = args.openclaw_setup_code or discover_openclaw_setup_code() or openclaw_setup_code_from_local_install()

    print("WakeClaw pairing helper")
    print(f"  Hermes:   {'found' if hermes_installed else 'not found'}; API port {'open' if hermes_port_open else 'not detected'}; dashboard {'open' if dashboard_port_open else 'not detected'}")
    print(f"  OpenClaw: {'found' if openclaw_installed else 'not found'}; gateway port {'open' if openclaw_port_open else 'not detected'}")
    print(f"  Tailscale:{' found' if tailscale_installed else ' not found'}")
    if discovered_hermes_url:
        print(f"  Hermes URL: detected {normalize_url(discovered_hermes_url)}")
    if hermes_key:
        print("  Hermes key: detected")
    if discovered_openclaw_setup_code:
        print("  OpenClaw setup code: detected")
    print()

    auto_hermes = bool(args.url or discovered_hermes_url or hermes_key or hermes_installed or hermes_port_open)
    auto_openclaw = bool(discovered_openclaw_setup_code or openclaw_installed or openclaw_port_open)
    if args.hermes_only:
        include_hermes = True
        include_openclaw = False
    elif args.openclaw_only:
        include_hermes = False
        include_openclaw = True
    elif args.interactive:
        include_hermes = ask_yes_no("Include detected Hermes Agent settings in this QR?", auto_hermes)
        include_openclaw = ask_yes_no("Include detected OpenClaw settings in this QR?", auto_openclaw)
    else:
        include_hermes = auto_hermes
        include_openclaw = auto_openclaw
    include_tailscale = tailscale_installed and not args.no_tailscale and (not args.interactive or ask_yes_no("Include Tailscale/VPN endpoint candidates?", True))
    use_public_tunnel = args.public_tunnel and not args.no_public_tunnel
    if (
        not use_public_tunnel
        and not args.no_public_tunnel
        and sys.stdin.isatty()
        and (shutil.which("ngrok") or shutil.which("cloudflared"))
        and (hermes_port_open or openclaw_port_open)
    ):
        use_public_tunnel = ask_yes_no(
            "Start temporary public tunnel URLs for phones that are not on your LAN/Tailscale?",
            False,
        )

    hermes_urls: List[str] = []
    hermes_public_url: Optional[str] = None
    terminal_pairing: Optional[dict] = discover_dashboard_terminal() if include_hermes and dashboard_port_open else None
    terminal_command_pairing: Optional[dict] = start_terminal_command_server() if include_openclaw and openclaw_installed else None
    openclaw_public_url: Optional[str] = None
    if use_public_tunnel:
        mux_public_url = None
        if include_hermes and include_openclaw and hermes_port_open and dashboard_port_open and openclaw_port_open and terminal_pairing:
            mux_public_url = start_multiplexed_public_tunnel(
                args.tunnel_provider,
                approval_port=(terminal_command_pairing or {}).get("port"),
            )
            if mux_public_url:
                hermes_public_url = mux_public_url + MUX_HERMES_PREFIX
                openclaw_public_url = mux_public_url
                terminal_pairing = dict(terminal_pairing)
                terminal_pairing["url"] = mux_public_url + MUX_DASHBOARD_PREFIX
                if terminal_command_pairing:
                    terminal_command_pairing = dict(terminal_command_pairing)
                    terminal_command_pairing["url"] = mux_public_url + MUX_TERMINAL_PREFIX + "/run"
                print(f"Started temporary public tunnel for Hermes/OpenClaw/Terminal: {mux_public_url}")
        if not mux_public_url and include_hermes and hermes_port_open:
            hermes_public_url = start_public_tunnel("http://127.0.0.1:8642", "hermes", args.tunnel_provider)
        if not mux_public_url and include_hermes and terminal_pairing and dashboard_port_open:
            dashboard_public_url = start_public_tunnel("http://127.0.0.1:9119", "hermes-dashboard", args.tunnel_provider)
            if dashboard_public_url and wait_for_http_url(dashboard_public_url, timeout_seconds=8.0):
                terminal_pairing = dict(terminal_pairing)
                terminal_pairing["url"] = dashboard_public_url
            elif use_public_tunnel:
                fallback = dashboard_terminal_url(args.no_tailscale, args.no_lan)
                terminal_pairing = dict(terminal_pairing)
                terminal_pairing["url"] = fallback
                print(
                    f"Hermes Terminal dashboard tunnel could not be verified; using {fallback} instead.",
                    file=sys.stderr,
                )
        if not mux_public_url and include_openclaw and openclaw_port_open:
            openclaw_public_url = start_public_tunnel("http://127.0.0.1:18789", "openclaw", args.tunnel_provider)
        if not mux_public_url and terminal_command_pairing and terminal_command_pairing.get("port"):
            terminal_public_url = start_public_tunnel(
                f"http://127.0.0.1:{terminal_command_pairing['port']}",
                "agentvoice-terminal",
                args.tunnel_provider,
            )
            if terminal_public_url:
                terminal_command_pairing = dict(terminal_command_pairing)
                terminal_command_pairing["url"] = terminal_public_url.rstrip("/") + "/run"
        if openclaw_public_url:
            configure_openclaw_allowed_origin(openclaw_public_url)

    if include_hermes:
        hermes_candidates: List[str] = []
        hermes_candidates.extend(comma_urls(args.url))
        if hermes_public_url:
            unique_append(hermes_candidates, hermes_public_url)
            if not wait_for_hermes_url(hermes_public_url, hermes_key, timeout_seconds=8.0):
                print(
                    f"Warning: Hermes public tunnel was created but local verification is still warming up: {hermes_public_url}",
                    file=sys.stderr,
                )
        if discovered_hermes_url and not hermes_public_url:
            unique_append(hermes_candidates, normalize_url(discovered_hermes_url))
        if not hermes_candidates:
            if hermes_installed:
                unique_append(hermes_candidates, "http://127.0.0.1:8642")
            elif args.interactive:
                raw = input("Hermes API Server URL [http://127.0.0.1:8642]: ").strip() or "http://127.0.0.1:8642"
                unique_append(hermes_candidates, normalize_url(raw))
        if hermes_port_open and not hermes_public_url:
            unique_append(hermes_candidates, "http://127.0.0.1:8642")
        if include_tailscale and not hermes_public_url:
            for ts_host in [tailscale_dns_name(), tailscale_ip()]:
                if not ts_host:
                    continue
                hermes_key = maybe_configure_hermes_remote_access(hermes_key, ts_host)
                ts_url = f"http://{ts_host}:8642"
                if hermes_models_endpoint_ok(ts_url, hermes_key, timeout=3.0):
                    unique_append(hermes_candidates, ts_url)
                else:
                    print(
                        f"Skipping Hermes Tailscale URL because /v1/models is not reachable yet: {ts_url}",
                        file=sys.stderr,
                    )
        lan = first_lan_ip()
        if lan and not hermes_public_url and not args.no_lan and (not args.interactive or ask_yes_no(f"Also include LAN URL http://{lan}:8642?", True)):
            lan_url = f"http://{lan}:8642"
            if hermes_models_endpoint_ok(lan_url, hermes_key, timeout=3.0):
                unique_append(hermes_candidates, lan_url)
            else:
                print(
                    f"Skipping Hermes LAN URL because /v1/models is not reachable yet: {lan_url}",
                    file=sys.stderr,
                )
        hermes_urls = ordered_android_urls(hermes_candidates)
        if hermes_key is None and args.interactive:
            hermes_key = getpass.getpass("Hermes API key (blank if none): ").strip() or None
        if not hermes_urls:
            print("Hermes was detected, but no URL could be resolved. Skipping Hermes; run with --interactive to enter it manually.", file=sys.stderr)
            include_hermes = False

    openclaw_setup_code: Optional[str] = None
    if include_openclaw:
        if args.openclaw_setup_code:
            openclaw_setup_code = args.openclaw_setup_code
        elif use_public_tunnel and openclaw_port_open:
            openclaw_setup_code = openclaw_setup_code_from_local_install() or discovered_openclaw_setup_code
        else:
            openclaw_setup_code = discovered_openclaw_setup_code or openclaw_setup_code_from_local_install()
        if openclaw_public_url and openclaw_setup_code and wait_for_http_url(openclaw_public_url.rstrip("/") + "/health"):
            openclaw_setup_code = setup_code_with_url(openclaw_setup_code, openclaw_public_url)
        elif openclaw_public_url and openclaw_setup_code:
            print(f"Skipping OpenClaw public tunnel because /health is not reachable yet: {openclaw_public_url}", file=sys.stderr)
            if use_public_tunnel:
                openclaw_setup_code = None
        elif use_public_tunnel and openclaw_port_open:
            print("OpenClaw public tunnel could not be created; refusing to emit a phone QR with a loopback/Tailscale-only URL.", file=sys.stderr)
            openclaw_setup_code = None
        if not openclaw_setup_code and args.interactive:
            print("Could not read OpenClaw setup code automatically.")
            print("Run `openclaw qr --setup-code-only` and paste the setup code below.")
            openclaw_setup_code = input("OpenClaw setup code: ").strip() or None
        if not openclaw_setup_code:
            print("OpenClaw was detected, but no setup code could be resolved. Skipping OpenClaw.", file=sys.stderr)
            include_openclaw = False
        openclaw_setup_code = setup_code_for_host_approval(openclaw_setup_code, terminal_command_pairing)

    if not include_hermes and not include_openclaw:
        raise SystemExit(
            "No pairable backend was found automatically. "
            "Start Hermes/OpenClaw or run `agentvoice-pair --interactive` to enter values manually."
        )

    qr_payload = build_pairing_json(
        hermes_urls=hermes_urls,
        hermes_key=hermes_key,
        model=hermes_model,
        use_runs_api=hermes_runs,
        streaming=hermes_streaming,
        display_name=args.name,
        terminal=terminal_pairing,
        openclaw_setup_code=openclaw_setup_code,
        openclaw_approval=terminal_command_pairing,
    )
    deep_link = build_pairing_uri(
        hermes_urls=hermes_urls,
        hermes_key=hermes_key,
        model=hermes_model,
        use_runs_api=hermes_runs,
        streaming=hermes_streaming,
        display_name=args.name,
        terminal=terminal_pairing,
        openclaw_setup_code=openclaw_setup_code,
        openclaw_approval=terminal_command_pairing,
    )

    qr_path = write_qr_png(qr_payload, Path(tempfile.gettempdir()) / "agentvoice-pair-qr.png")
    write_qr_svg(qr_payload, Path(tempfile.gettempdir()) / "agentvoice-pair-qr.svg")
    terminal_width, terminal_height = terminal_qr_size(qr_payload, compact = not args.large_qr)
    cols, rows = shutil.get_terminal_size(fallback=(80, 24))
    fits_terminal = terminal_width <= cols and terminal_height + 8 <= rows

    print("\nScan this one QR inside WakeClaw:")
    opened = False if args.no_open else open_file(qr_path)
    print(f"  QR image: {qr_path}")
    if opened:
        print("  Opened the QR image in your desktop viewer.")
    else:
        print("  Open this file if the terminal QR does not fit on screen.")
    if fits_terminal or (args.terminal_qr and not args.no_terminal_qr) or args.large_qr:
        print()
        rendered = render_qr(qr_payload, compact = not args.large_qr)
        if not rendered:
            print("(QR rendering unavailable on this machine.)")
    else:
        print(f"  Terminal QR omitted because it is {terminal_width}x{terminal_height} cells and your terminal is {cols}x{rows}.")
    print("\nQR payload for WakeClaw in-app scanner:")
    print(f"  {qr_payload}\n")
    print("External-camera fallback link:")
    print(f"  {deep_link}\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
