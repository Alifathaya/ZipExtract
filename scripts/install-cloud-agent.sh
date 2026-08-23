#!/usr/bin/env bash
# Install cloud-agent SSH access (TrendBot-compatible bootstrap).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
bash "$ROOT/scripts/setup-cloud-agent-ssh.sh"
HOST="${VPS_HOST:-62.238.96.225}"
ssh -i ~/.ssh/id_ed25519 -o BatchMode=yes -o StrictHostKeyChecking=accept-new \
  "root@${HOST}" hostname
