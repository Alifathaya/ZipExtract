#!/usr/bin/env bash
# Cursor Cloud agent start — ensure VPS SSH is ready for this pod.
# Always non-fatal: hub downtime must not block agent start.
set -u
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
bash "${ROOT}/scripts/setup-cloud-agent-ssh.sh" || true
exit 0
