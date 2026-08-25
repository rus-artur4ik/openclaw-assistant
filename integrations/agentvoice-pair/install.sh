#!/usr/bin/env bash
set -euo pipefail

REPO_RAW_BASE="${AGENT_VOICE_RAW_BASE:-https://raw.githubusercontent.com/rus-artur4ik/openclaw-assistant/main}"
INSTALL_DIR="${AGENT_VOICE_INSTALL_DIR:-$HOME/.local/bin}"
SCRIPT_URL="$REPO_RAW_BASE/integrations/hermes-mobile-bridge/hermes_pair.py"
TARGET="$INSTALL_DIR/agentvoice-pair"

mkdir -p "$INSTALL_DIR"

if ! command -v python3 >/dev/null 2>&1; then
  echo "error: python3 is required" >&2
  exit 1
fi

tmp="$(mktemp)"
trap 'rm -f "$tmp"' EXIT

curl -fsSL "$SCRIPT_URL" -o "$tmp"
python3 -m py_compile "$tmp"
install -m 0755 "$tmp" "$TARGET"

echo "Installed agentvoice-pair to $TARGET"
case ":$PATH:" in
  *":$INSTALL_DIR:"*) ;;
  *)
    echo "Add this to your shell profile if agentvoice-pair is not found:"
    echo "  export PATH=\"$INSTALL_DIR:\$PATH\""
    ;;
esac
echo "Run: agentvoice-pair"
