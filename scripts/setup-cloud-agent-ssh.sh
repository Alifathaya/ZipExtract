#!/usr/bin/env bash
# Fetch the cloud-agent SSH private key from the Helsinki VPS bootstrap API
# and install it to ~/.ssh/id_ed25519 for passwordless root access.
set -euo pipefail

HOST="${VPS_HOST:-62.238.96.225}"
KEY_URL="${VPS_SSH_KEY_URL:-http://${HOST}:8088/api/cloud-agent-ssh-key}"
TOKEN_FILE="$(cd "$(dirname "$0")" && pwd)/.vps-agent-bootstrap-token"
KEY_PATH="${HOME}/.ssh/id_ed25519"

if [[ -n "${VPS_AGENT_BOOTSTRAP_TOKEN:-}" ]]; then
  TOKEN="$VPS_AGENT_BOOTSTRAP_TOKEN"
elif [[ -f "$TOKEN_FILE" ]]; then
  TOKEN="$(tr -d '[:space:]' < "$TOKEN_FILE")"
else
  echo "Missing bootstrap token." >&2
  echo "Create scripts/.vps-agent-bootstrap-token or set VPS_AGENT_BOOTSTRAP_TOKEN." >&2
  exit 1
fi

mkdir -p "${HOME}/.ssh"
chmod 700 "${HOME}/.ssh"

echo "Fetching cloud-agent SSH key from ${KEY_URL} ..."
HTTP_CODE=$(curl -sS -o "$KEY_PATH" -w '%{http_code}' \
  -H "Authorization: Bearer ${TOKEN}" \
  --max-time 30 \
  "$KEY_URL")

if [[ "$HTTP_CODE" != "200" ]]; then
  echo "Failed to fetch key (HTTP ${HTTP_CODE}):" >&2
  head -c 200 "$KEY_PATH" >&2 || true
  echo >&2
  rm -f "$KEY_PATH"
  exit 1
fi

# Accept raw PEM or JSON {"privateKey":"..."} / {"key":"..."}
if head -1 "$KEY_PATH" | grep -q '{'; then
  python3 - "$KEY_PATH" <<'PY'
import json, sys
path = sys.argv[1]
data = json.load(open(path))
key = data.get("privateKey") or data.get("private_key") or data.get("key")
if not key:
    raise SystemExit("JSON response missing private key field")
open(path, "w").write(key if key.endswith("\n") else key + "\n")
PY
fi

chmod 600 "$KEY_PATH"
ssh-keygen -y -f "$KEY_PATH" > "${KEY_PATH}.pub" 2>/dev/null || true
chmod 644 "${KEY_PATH}.pub" 2>/dev/null || true

echo "Installed ${KEY_PATH}"
echo "Test with:"
echo "  ssh -i ~/.ssh/id_ed25519 -o BatchMode=yes -o StrictHostKeyChecking=accept-new root@${HOST} hostname"
