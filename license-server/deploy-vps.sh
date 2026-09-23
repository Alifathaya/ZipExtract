#!/usr/bin/env bash
# Usage (on VPS as root):
#   curl -fsSL ... | bash
# Or copy this file to the VPS and: bash deploy-vps.sh
set -euo pipefail
APP_DIR=/opt/filenest/license-server
REPO_DIR=/opt/filenest
DOMAIN="${LICENSE_DOMAIN:-license.dwi.heryanto.com}"
ADMIN_PASSWORD="${ADMIN_PASSWORD:?Set ADMIN_PASSWORD}"
PORT="${PORT:-8787}"

if ! command -v node >/dev/null; then
  curl -fsSL https://deb.nodesource.com/setup_20.x | bash -
  apt-get install -y nodejs
fi
apt-get install -y git nginx certbot python3-certbot-nginx

if [[ ! -d "$REPO_DIR/.git" ]]; then
  git clone https://github.com/Alifathaya/ZipExtract.git "$REPO_DIR"
fi
cd "$REPO_DIR"
git fetch origin
git checkout main || true
git pull origin main || git pull

cd "$APP_DIR"
npm install --omit=dev

mkdir -p "$APP_DIR/data"
cat > "$APP_DIR/.env" <<ENV
PORT=$PORT
ADMIN_PASSWORD=$ADMIN_PASSWORD
TRIAL_DAYS=30
DATA_DIR=$APP_DIR/data
ENV

cat > /etc/systemd/system/filenest-license.service <<UNIT
[Unit]
Description=FileNest License API
After=network.target

[Service]
WorkingDirectory=$APP_DIR
EnvironmentFile=$APP_DIR/.env
ExecStart=/usr/bin/node src/server.js
Restart=always
User=root

[Install]
WantedBy=multi-user.target
UNIT

systemctl daemon-reload
systemctl enable --now filenest-license

cat > /etc/nginx/sites-available/filenest-license <<NGX
server {
    server_name $DOMAIN;
    location / {
        proxy_pass http://127.0.0.1:$PORT;
        proxy_http_version 1.1;
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \$scheme;
    }
}
NGX
ln -sf /etc/nginx/sites-available/filenest-license /etc/nginx/sites-enabled/
nginx -t
systemctl reload nginx
certbot --nginx -d "$DOMAIN" --non-interactive --agree-tos -m admin@$DOMAIN || true

systemctl status filenest-license --no-pager
curl -sS "http://127.0.0.1:$PORT/health" || true
echo "Admin: https://$DOMAIN/admin.html"
