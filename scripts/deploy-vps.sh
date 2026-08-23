#!/usr/bin/env bash
# Deploy FileNest license-server to Helsinki VPS (TrendBot host).
# Usage: ./scripts/deploy-vps.sh --branch cursor/license-server-monthly-c8f7
set -euo pipefail

BRANCH="main"
HOST="${VPS_HOST:-62.238.96.225}"
REMOTE_DIR="/opt/filenest"
LICENSE_DIR="${REMOTE_DIR}/license-server"
DOMAIN="${LICENSE_DOMAIN:-license.dwi.heryanto.com}"
ADMIN_PASSWORD="${ADMIN_PASSWORD:-FileNestAdmin@1309}"
PORT="${PORT:-8787}"
SSH_KEY="${HOME}/.ssh/id_ed25519"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --branch) BRANCH="$2"; shift 2 ;;
    --host) HOST="$2"; shift 2 ;;
    --domain) DOMAIN="$2"; shift 2 ;;
    --admin-password) ADMIN_PASSWORD="$2"; shift 2 ;;
    *) echo "Unknown arg: $1" >&2; exit 1 ;;
  esac
done

if [[ ! -f "$SSH_KEY" ]]; then
  echo "SSH key missing — run: bash scripts/setup-cloud-agent-ssh.sh" >&2
  exit 1
fi

SSH=(ssh -i "$SSH_KEY" -o BatchMode=yes -o StrictHostKeyChecking=accept-new "root@${HOST}")

echo "Remote hostname check..."
"${SSH[@]}" hostname

echo "Deploying branch ${BRANCH} to ${HOST}:${LICENSE_DIR} ..."
"${SSH[@]}" bash -s -- "$BRANCH" "$REMOTE_DIR" "$LICENSE_DIR" "$DOMAIN" "$ADMIN_PASSWORD" "$PORT" <<'REMOTE'
set -euo pipefail
BRANCH="$1"; REMOTE_DIR="$2"; LICENSE_DIR="$3"; DOMAIN="$4"; ADMIN_PASSWORD="$5"; PORT="$6"

export DEBIAN_FRONTEND=noninteractive
if ! command -v node >/dev/null 2>&1; then
  curl -fsSL https://deb.nodesource.com/setup_20.x | bash -
  apt-get install -y nodejs
fi
apt-get install -y git nginx certbot python3-certbot-nginx

if [[ ! -d "${REMOTE_DIR}/.git" ]]; then
  git clone https://github.com/Alifathaya/ZipExtract.git "$REMOTE_DIR"
fi
cd "$REMOTE_DIR"
git fetch origin
git checkout -B "$BRANCH" "origin/${BRANCH}" || git checkout "$BRANCH"
git pull --ff-only origin "$BRANCH" || true

cd "$LICENSE_DIR"
npm install --omit=dev
mkdir -p data
cat > .env <<ENV
PORT=${PORT}
ADMIN_PASSWORD=${ADMIN_PASSWORD}
TRIAL_DAYS=30
DATA_DIR=${LICENSE_DIR}/data
ENV

cat > /etc/systemd/system/filenest-license.service <<UNIT
[Unit]
Description=FileNest License API
After=network.target

[Service]
WorkingDirectory=${LICENSE_DIR}
EnvironmentFile=${LICENSE_DIR}/.env
ExecStart=/usr/bin/node src/server.js
Restart=always
User=root

[Install]
WantedBy=multi-user.target
UNIT

systemctl daemon-reload
systemctl enable --now filenest-license
systemctl restart filenest-license

cat > /etc/nginx/sites-available/filenest-license <<NGX
server {
    listen 80;
    server_name ${DOMAIN};
    location / {
        proxy_pass http://127.0.0.1:${PORT};
        proxy_http_version 1.1;
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \$scheme;
    }
}
NGX
ln -sf /etc/nginx/sites-available/filenest-license /etc/nginx/sites-enabled/filenest-license
nginx -t
systemctl reload nginx

# Certbot only if DNS already points here
if getent hosts "$DOMAIN" | grep -q "$(curl -4 -s --max-time 5 ifconfig.me || true)"; then
  certbot --nginx -d "$DOMAIN" --non-interactive --agree-tos --register-unsafely-without-email || true
else
  echo "WARN: DNS for ${DOMAIN} does not point to this VPS yet — skip certbot"
fi

systemctl --no-pager --full status filenest-license | head -20
curl -sS "http://127.0.0.1:${PORT}/health"
echo
echo "Admin (HTTP): http://${DOMAIN}/admin.html"
REMOTE

echo "Done."
