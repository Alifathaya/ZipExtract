#!/usr/bin/env bash
# Cursor Cloud agent install — idempotent repo bootstrap + VPS SSH.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT}"

_apt_install() {
  if command -v sudo >/dev/null 2>&1; then
    sudo DEBIAN_FRONTEND=noninteractive apt-get "$@"
  else
    DEBIAN_FRONTEND=noninteractive apt-get "$@"
  fi
}

_ensure_tools() {
  local need=()
  command -v curl >/dev/null 2>&1 || need+=(curl)
  command -v ssh >/dev/null 2>&1 || need+=(openssh-client)
  command -v ssh-keygen >/dev/null 2>&1 || need+=(openssh-client)
  if ((${#need[@]} == 0)); then
    return 0
  fi
  if ! command -v apt-get >/dev/null 2>&1; then
    echo "WARN: missing tools (${need[*]}) and apt-get unavailable" >&2
    return 0
  fi
  echo "==> Installing: ${need[*]}"
  _apt_install update -qq || true
  _apt_install install -y -qq "${need[@]}" || true
}

_ensure_tools

# Android Gradle wrapper is committed; no heavy SDK install here (snapshot/base image).
if [[ -x ./gradlew ]]; then
  echo "==> Gradle wrapper present"
fi

# Non-fatal: VPS SSH optional during snapshot build if hub is unreachable.
bash scripts/setup-cloud-agent-ssh.sh || true

echo "Cloud agent install complete"
