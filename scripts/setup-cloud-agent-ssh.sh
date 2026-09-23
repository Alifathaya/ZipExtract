#!/usr/bin/env bash
# Bootstrap passwordless SSH to the Helsinki VPS for Cursor Cloud Agents.
# Idempotent. Non-fatal: always exits 0 so install/start snapshots stay green
# if the hub is temporarily unreachable.
set -u

DEPLOY_HOST="${DEPLOY_HOST:-${VPS_HOST:-62.238.96.225}}"
DEPLOY_USER="${DEPLOY_USER:-root}"
HUB_PORT="${TRENDBOT_HUB_PORT:-${VPS_HUB_PORT:-8088}}"
SSH_ALIAS="${VPS_SSH_ALIAS:-trendbot-helsinki}"
KEY="${HOME}/.ssh/id_ed25519"
PUB="${KEY}.pub"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TOKEN_FILE="${ROOT}/scripts/.vps-agent-bootstrap-token"
KEY_URL="${VPS_SSH_KEY_URL:-http://${DEPLOY_HOST}:${HUB_PORT}/api/cloud-agent-ssh-key}"

mkdir -p "${HOME}/.ssh"
chmod 700 "${HOME}/.ssh"

_write_key() {
  # $1 = private key contents (OpenSSH PEM)
  printf '%s\n' "$1" > "${KEY}"
  chmod 600 "${KEY}"
  if ! ssh-keygen -y -f "${KEY}" > "${PUB}" 2>/dev/null; then
    echo "Invalid private key written to ${KEY}" >&2
    rm -f "${KEY}" "${PUB}"
    return 1
  fi
  chmod 644 "${PUB}"
}

_ensure_ssh_config() {
  local cfg="${HOME}/.ssh/config"
  touch "${cfg}"
  chmod 600 "${cfg}"
  if grep -qE "^[[:space:]]*Host[[:space:]]+${SSH_ALIAS}([[:space:]]|$)" "${cfg}" 2>/dev/null; then
    return 0
  fi
  cat >> "${cfg}" <<EOF

Host ${SSH_ALIAS}
  HostName ${DEPLOY_HOST}
  User ${DEPLOY_USER}
  IdentityFile ${KEY}
  IdentitiesOnly yes
  BatchMode yes
  StrictHostKeyChecking accept-new
EOF
  echo "Wrote SSH Host alias '${SSH_ALIAS}' → ${DEPLOY_USER}@${DEPLOY_HOST}"
}

_verify_ssh() {
  if [[ ! -f "${KEY}" ]]; then
    return 1
  fi
  if ssh -i "${KEY}" -o BatchMode=yes -o StrictHostKeyChecking=accept-new \
      -o ConnectTimeout=15 \
      "${DEPLOY_USER}@${DEPLOY_HOST}" hostname >/dev/null 2>&1; then
    echo "VPS SSH OK: ${DEPLOY_USER}@${DEPLOY_HOST} (alias: ${SSH_ALIAS})"
    return 0
  fi
  # Also try the Host alias once config is in place.
  if ssh -o BatchMode=yes -o ConnectTimeout=15 "${SSH_ALIAS}" hostname >/dev/null 2>&1; then
    echo "VPS SSH OK via alias '${SSH_ALIAS}'"
    return 0
  fi
  echo "VPS SSH BatchMode check failed for ${DEPLOY_USER}@${DEPLOY_HOST}" >&2
  return 1
}

_bootstrap_from_hub() {
  local token="" key_content="" http_body=""
  if [[ -n "${VPS_AGENT_BOOTSTRAP_TOKEN:-}" ]]; then
    token="$(printf '%s' "${VPS_AGENT_BOOTSTRAP_TOKEN}" | tr -d '[:space:]')"
  elif [[ -f "${TOKEN_FILE}" ]]; then
    token="$(tr -d '[:space:]' < "${TOKEN_FILE}")"
  fi
  if [[ -z "${token}" ]]; then
    echo "Missing bootstrap token (${TOKEN_FILE})" >&2
    return 1
  fi

  echo "Fetching cloud-agent SSH key from ${KEY_URL} ..."
  http_body="$(
    curl -sf --max-time 20 \
      -H "Authorization: Bearer ${token}" \
      "${KEY_URL}" \
    || true
  )"
  if [[ -z "${http_body}" ]]; then
    echo "Hub bootstrap failed (empty/non-200 response)" >&2
    return 1
  fi

  key_content="${http_body}"
  if [[ "${http_body}" == "{"* ]]; then
    key_content="$(
      KEY_JSON="${http_body}" python3 - <<'PY' || true
import json, os
data = json.loads(os.environ["KEY_JSON"])
key = data.get("privateKey") or data.get("private_key") or data.get("key") or ""
print(key)
PY
    )"
  fi

  if [[ "${key_content}" != *"BEGIN OPENSSH PRIVATE KEY"* && "${key_content}" != *"BEGIN RSA PRIVATE KEY"* ]]; then
    echo "Hub response was not an OpenSSH private key" >&2
    return 1
  fi

  _write_key "${key_content}" || return 1
  echo "VPS SSH key bootstrapped from hub ${DEPLOY_HOST}:${HUB_PORT}"
  return 0
}

# --- main ---
_ensure_ssh_config

if [[ -f "${KEY}" ]]; then
  echo "VPS SSH key already present: ${KEY}"
else
  if ! _bootstrap_from_hub; then
    echo "VPS SSH key not configured — hub bootstrap unavailable (non-fatal)" >&2
    exit 0
  fi
fi

_ensure_ssh_config
_verify_ssh || true
exit 0
