# FileNest License Server

Node.js API + mobile admin panel for monthly device licenses.

## Requirements

- Node.js 18+
- VPS (e.g. Helsinki) with a domain + HTTPS (Nginx / Caddy)

## Quick start

```bash
cd license-server
cp .env.example .env
# edit ADMIN_PASSWORD in .env (or export env vars)
npm install
ADMIN_PASSWORD='your-strong-password' PORT=8787 node src/server.js
```

Open admin: `http://YOUR_IP:8787/admin.html`

## Production (systemd sketch)

```ini
[Unit]
Description=FileNest License API
After=network.target

[Service]
WorkingDirectory=/opt/filenest-license
Environment=PORT=8787
Environment=ADMIN_PASSWORD=your-strong-password
Environment=TRIAL_DAYS=30
Environment=DATA_DIR=/opt/filenest-license/data
ExecStart=/usr/bin/node src/server.js
Restart=always
User=www-data

[Install]
WantedBy=multi-user.target
```

Put Nginx/Caddy in front with HTTPS, proxy to `127.0.0.1:8787`.

## Android app

Default API base (no trailing slash):

```text
http://62.238.96.225
```

Admin panel: `http://62.238.96.225/admin.html`

Override in `local.properties` or CI env if needed:

```properties
LICENSE_API_BASE_URL=http://62.238.96.225
```

Cleartext HTTP to that IP is allowed via `network_security_config.xml`.

## API summary

| Method | Path | Purpose |
|--------|------|---------|
| POST | `/v1/license/register` | First launch / restore device |
| POST | `/v1/license/check` | Daily / open-app check |
| POST | `/v1/license/activate` | Redeem 12-char key |
| POST | `/v1/admin/login` | Admin token |
| POST | `/v1/admin/keys/generate` | Create keys |
| GET | `/v1/admin/devices` | List devices |
