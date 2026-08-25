'use strict';

/**
 * FileNest license server — API + admin UI.
 *
 * Env:
 *   PORT              default 8787
 *   ADMIN_PASSWORD    required in production (default: changeme)
 *   TRIAL_DAYS        default 30
 *   DATA_DIR          default ./data
 */

const path = require('path');
const fs = require('fs');
const http = require('http');
const crypto = require('crypto');
const express = require('express');
const cors = require('cors');
const rateLimit = require('express-rate-limit');
const Database = require('better-sqlite3');
const { WebSocketServer } = require('ws');

const PORT = Number(process.env.PORT || 8787);
const ADMIN_PASSWORD = process.env.ADMIN_PASSWORD || 'changeme';
const TRIAL_DAYS = Number(process.env.TRIAL_DAYS || 30);
const DATA_DIR = process.env.DATA_DIR || path.join(__dirname, '..', 'data');

fs.mkdirSync(DATA_DIR, { recursive: true });
const db = new Database(path.join(DATA_DIR, 'license.sqlite'));
db.pragma('journal_mode = WAL');

db.exec(`
CREATE TABLE IF NOT EXISTS devices (
  device_id TEXT PRIMARY KEY,
  created_at TEXT NOT NULL,
  expires_at TEXT NOT NULL,
  last_check_at TEXT,
  status TEXT NOT NULL DEFAULT 'active',
  app_version TEXT,
  note TEXT
);

CREATE TABLE IF NOT EXISTS keys (
  key_code TEXT PRIMARY KEY,
  days INTEGER NOT NULL DEFAULT 30,
  created_at TEXT NOT NULL,
  used_at TEXT,
  used_by_device TEXT,
  status TEXT NOT NULL DEFAULT 'unused'
);

CREATE TABLE IF NOT EXISTS admin_tokens (
  token TEXT PRIMARY KEY,
  created_at TEXT NOT NULL,
  expires_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS events (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  at TEXT NOT NULL,
  kind TEXT NOT NULL,
  device_id TEXT,
  detail TEXT
);
`);

/** Supported products in the shared license DB / admin UI. */
const APP_IDS = ['filenest', 'brilink'];
const DEFAULT_APP_ID = 'filenest';

function ensureColumn(table, column, ddl) {
  const cols = db.prepare(`PRAGMA table_info(${table})`).all().map((c) => c.name);
  if (!cols.includes(column)) {
    db.exec(`ALTER TABLE ${table} ADD COLUMN ${column} ${ddl}`);
  }
}

ensureColumn('devices', 'app_id', `TEXT NOT NULL DEFAULT '${DEFAULT_APP_ID}'`);
ensureColumn('keys', 'app_id', `TEXT NOT NULL DEFAULT '${DEFAULT_APP_ID}'`);
db.exec(`CREATE INDEX IF NOT EXISTS idx_devices_app ON devices(app_id)`);
db.exec(`CREATE INDEX IF NOT EXISTS idx_keys_app ON keys(app_id)`);

function normalizeAppId(raw, { strict = false } = {}) {
  let id = String(raw || '')
    .trim()
    .toLowerCase()
    .replace(/[\s_-]+/g, '');
  if (id === 'bri' || id === 'brilink' || id === 'brilinks') id = 'brilink';
  if (id === 'filenest' || id === 'zipextract') id = 'filenest';
  if (APP_IDS.includes(id)) return id;
  return strict ? null : DEFAULT_APP_ID;
}

const insertEvent = db.prepare(
  `INSERT INTO events (at, kind, device_id, detail) VALUES (?, ?, ?, ?)`,
);

function nowIso() {
  return new Date().toISOString();
}

/** Far-future expiry used for lifetime / unlimited licenses. */
const UNLIMITED_EXPIRES = '9999-12-31T23:59:59.000Z';
/** Stored in keys.days — 0 means unlimited lifetime. */
const UNLIMITED_KEY_DAYS = 0;

function isUnlimitedExpires(iso) {
  if (!iso) return false;
  const t = new Date(iso).getTime();
  if (!Number.isFinite(t)) return String(iso).startsWith('9999');
  // Anything in year 9000+ is treated as unlimited.
  return new Date(iso).getUTCFullYear() >= 9000;
}

function addDays(isoOrDate, days) {
  const d = new Date(isoOrDate);
  d.setUTCDate(d.getUTCDate() + days);
  return d.toISOString();
}

function logEvent(kind, deviceId, detail) {
  try {
    insertEvent.run(nowIso(), kind, deviceId || null, detail || null);
  } catch (_) {
    /* ignore */
  }
}

/** 12 chars: letters, digits, symbols — avoids 0/O/1/l/I. */
const KEY_ALPHABET =
  'ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789#@!$%&*+-';

function generateKeyCode() {
  const bytes = crypto.randomBytes(12);
  let out = '';
  for (let i = 0; i < 12; i++) {
    out += KEY_ALPHABET[bytes[i] % KEY_ALPHABET.length];
  }
  return out;
}

function devicePayload(row) {
  const serverTime = nowIso();
  const unlimited = isUnlimitedExpires(row.expires_at);
  const expired = !unlimited && new Date(row.expires_at).getTime() <= Date.now();
  const blocked = row.status === 'blocked';
  let status = 'active';
  if (blocked) status = 'blocked';
  else if (expired) status = 'expired';
  return {
    status,
    expiresAt: row.expires_at,
    serverTime,
    trialDays: TRIAL_DAYS,
    unlimited,
  };
}

const app = express();
app.set('trust proxy', 1);
app.use(cors());
app.use(express.json({ limit: '32kb' }));
app.use(express.static(path.join(__dirname, '..', 'public')));

const apiLimiter = rateLimit({
  windowMs: 60 * 1000,
  max: 60,
  standardHeaders: true,
  legacyHeaders: false,
});
const activateLimiter = rateLimit({
  windowMs: 15 * 60 * 1000,
  max: 20,
  standardHeaders: true,
  legacyHeaders: false,
});
const adminLoginLimiter = rateLimit({
  windowMs: 15 * 60 * 1000,
  max: 30,
  standardHeaders: true,
  legacyHeaders: false,
});

app.use('/v1', apiLimiter);

function requireAdmin(req, res, next) {
  const header = req.headers.authorization || '';
  const token = header.startsWith('Bearer ') ? header.slice(7) : '';
  if (!token) {
    return res.status(401).json({ error: 'unauthorized' });
  }
  const row = db
    .prepare(
      `SELECT token FROM admin_tokens WHERE token = ? AND expires_at > ?`,
    )
    .get(token, nowIso());
  if (!row) {
    return res.status(401).json({ error: 'unauthorized' });
  }
  next();
}

app.get('/health', (_req, res) => {
  res.json({ ok: true, time: nowIso() });
});

app.post('/v1/license/register', (req, res) => {
  const deviceId = String(req.body?.deviceId || '').trim();
  const appVersion = String(req.body?.appVersion || '').trim() || null;
  const appId = normalizeAppId(req.body?.appId);
  if (!deviceId || deviceId.length < 8 || deviceId.length > 128) {
    return res.status(400).json({ error: 'invalid_device_id' });
  }

  const existing = db
    .prepare(`SELECT * FROM devices WHERE device_id = ?`)
    .get(deviceId);
  if (existing) {
    db.prepare(
      `UPDATE devices SET last_check_at = ?, app_version = COALESCE(?, app_version) WHERE device_id = ?`,
    ).run(nowIso(), appVersion, deviceId);
    logEvent('register_existing', deviceId, `${existing.app_id || appId}:${appVersion || ''}`);
    return res.json(devicePayload(existing));
  }

  const created = nowIso();
  const expires = addDays(created, TRIAL_DAYS);
  db.prepare(
    `INSERT INTO devices (device_id, created_at, expires_at, last_check_at, status, app_version, app_id)
     VALUES (?, ?, ?, ?, 'active', ?, ?)`,
  ).run(deviceId, created, expires, created, appVersion, appId);
  logEvent('register_new', deviceId, `${appId}:trial_${TRIAL_DAYS}d`);
  const row = db.prepare(`SELECT * FROM devices WHERE device_id = ?`).get(deviceId);
  return res.json(devicePayload(row));
});

app.post('/v1/license/check', (req, res) => {
  const deviceId = String(req.body?.deviceId || '').trim();
  if (!deviceId) {
    return res.status(400).json({ error: 'invalid_device_id' });
  }
  const row = db.prepare(`SELECT * FROM devices WHERE device_id = ?`).get(deviceId);
  if (!row) {
    return res.status(404).json({ error: 'unknown_device', status: 'unknown' });
  }
  db.prepare(`UPDATE devices SET last_check_at = ? WHERE device_id = ?`).run(
    nowIso(),
    deviceId,
  );
  const payload = devicePayload(row);
  logEvent('check', deviceId, payload.status);
  return res.json(payload);
});

app.post('/v1/license/activate', activateLimiter, (req, res) => {
  const deviceId = String(req.body?.deviceId || '').trim();
  const key = String(req.body?.key || '').trim();
  const appId = normalizeAppId(req.body?.appId);
  if (!deviceId || key.length !== 12) {
    return res.status(400).json({ error: 'invalid_request' });
  }

  const keyRow = db.prepare(`SELECT * FROM keys WHERE key_code = ?`).get(key);
  if (!keyRow || keyRow.status === 'revoked') {
    logEvent('activate_fail', deviceId, 'invalid_key');
    return res.status(400).json({ error: 'invalid_key' });
  }
  if (keyRow.status === 'used') {
    logEvent('activate_fail', deviceId, 'key_used');
    return res.status(400).json({ error: 'key_already_used' });
  }
  const keyApp = normalizeAppId(keyRow.app_id);
  if (keyApp !== appId) {
    logEvent('activate_fail', deviceId, `wrong_app:${keyApp}`);
    return res.status(400).json({ error: 'key_wrong_app' });
  }

  let device = db.prepare(`SELECT * FROM devices WHERE device_id = ?`).get(deviceId);
  const now = nowIso();
  const unlimitedKey = Number(keyRow.days) === UNLIMITED_KEY_DAYS;
  if (!device) {
    const expires = unlimitedKey ? UNLIMITED_EXPIRES : addDays(now, keyRow.days);
    db.prepare(
      `INSERT INTO devices (device_id, created_at, expires_at, last_check_at, status, app_id)
       VALUES (?, ?, ?, ?, 'active', ?)`,
    ).run(deviceId, now, expires, now, appId);
  } else {
    if (device.status === 'blocked') {
      return res.status(403).json({ error: 'device_blocked' });
    }
    const deviceApp = normalizeAppId(device.app_id);
    if (deviceApp !== appId) {
      return res.status(400).json({ error: 'device_wrong_app' });
    }
    let expires;
    if (unlimitedKey) {
      expires = UNLIMITED_EXPIRES;
    } else {
      const base =
        new Date(device.expires_at).getTime() > Date.now() &&
        !isUnlimitedExpires(device.expires_at)
          ? device.expires_at
          : now;
      expires = addDays(base, keyRow.days);
    }
    db.prepare(
      `UPDATE devices SET expires_at = ?, last_check_at = ?, status = 'active', app_id = ? WHERE device_id = ?`,
    ).run(expires, now, appId, deviceId);
  }

  db.prepare(
    `UPDATE keys SET status = 'used', used_at = ?, used_by_device = ? WHERE key_code = ?`,
  ).run(now, deviceId, key);

  device = db.prepare(`SELECT * FROM devices WHERE device_id = ?`).get(deviceId);
  logEvent('activate_ok', deviceId, unlimitedKey ? `${appId}:unlimited` : `${appId}:${key}`);
  return res.json({
    ...devicePayload(device),
    daysAdded: unlimitedKey ? null : keyRow.days,
  });
});

app.post('/v1/admin/login', adminLoginLimiter, (req, res) => {
  const password = String(req.body?.password || '');
  if (password !== ADMIN_PASSWORD) {
    return res.status(401).json({ error: 'bad_password' });
  }
  const token = crypto.randomBytes(32).toString('hex');
  const created = nowIso();
  const expires = addDays(created, 7);
  db.prepare(
    `INSERT INTO admin_tokens (token, created_at, expires_at) VALUES (?, ?, ?)`,
  ).run(token, created, expires);
  return res.json({ token, expiresAt: expires });
});

app.post('/v1/admin/keys/generate', requireAdmin, (req, res) => {
  const appId = normalizeAppId(req.body?.appId, { strict: true });
  if (!appId) {
    return res.status(400).json({ error: 'invalid_app', apps: APP_IDS });
  }
  const unlimited = Boolean(req.body?.unlimited) || Number(req.body?.days) === 0;
  const days = unlimited
    ? UNLIMITED_KEY_DAYS
    : Math.min(3650, Math.max(1, Number(req.body?.days) || TRIAL_DAYS));
  const count = Math.min(50, Math.max(1, Number(req.body?.count) || 1));
  const created = nowIso();
  const insert = db.prepare(
    `INSERT INTO keys (key_code, days, created_at, status, app_id) VALUES (?, ?, ?, 'unused', ?)`,
  );
  const keys = [];
  const tx = db.transaction(() => {
    for (let i = 0; i < count; i++) {
      let code;
      for (let attempt = 0; attempt < 20; attempt++) {
        code = generateKeyCode();
        try {
          insert.run(code, days, created, appId);
          keys.push({
            key: code,
            days,
            unlimited,
            appId,
            createdAt: created,
          });
          break;
        } catch (_) {
          /* collision */
        }
      }
    }
  });
  tx();
  logEvent(
    'admin_generate',
    null,
    unlimited
      ? `${appId}:${keys.length}x_unlimited`
      : `${appId}:${keys.length}x${days}d`,
  );
  return res.json({ keys, appId });
});

app.get('/v1/admin/keys', requireAdmin, (req, res) => {
  const appId = normalizeAppId(req.query.app, { strict: true });
  if (!appId) {
    return res.status(400).json({ error: 'invalid_app', apps: APP_IDS });
  }
  const status = String(req.query.status || '').trim();
  let rows;
  if (status) {
    rows = db
      .prepare(
        `SELECT * FROM keys WHERE app_id = ? AND status = ? ORDER BY created_at DESC LIMIT 200`,
      )
      .all(appId, status);
  } else {
    rows = db
      .prepare(
        `SELECT * FROM keys WHERE app_id = ? ORDER BY created_at DESC LIMIT 200`,
      )
      .all(appId);
  }
  return res.json({ keys: rows, appId });
});

app.get('/v1/admin/devices', requireAdmin, (req, res) => {
  const appId = normalizeAppId(req.query.app, { strict: true });
  if (!appId) {
    return res.status(400).json({ error: 'invalid_app', apps: APP_IDS });
  }
  const rows = db
    .prepare(
      `SELECT * FROM devices WHERE app_id = ? ORDER BY created_at DESC LIMIT 500`,
    )
    .all(appId);
  return res.json({
    devices: rows.map((r) => ({ ...r, ...devicePayload(r) })),
    appId,
  });
});

app.post('/v1/admin/devices/:id/block', requireAdmin, (req, res) => {
  const id = req.params.id;
  const info = db
    .prepare(`UPDATE devices SET status = 'blocked' WHERE device_id = ?`)
    .run(id);
  if (info.changes === 0) return res.status(404).json({ error: 'not_found' });
  logEvent('admin_block', id, null);
  const row = db.prepare(`SELECT * FROM devices WHERE device_id = ?`).get(id);
  pushLicenseToDevice(id, devicePayload(row));
  return res.json({ ok: true, ...devicePayload(row) });
});

/**
 * Clear blocked status and/or extend license.
 * - unlimited=true: set lifetime expiry (never expires by date).
 * - If expires_at is still in the future: keep that date (resume remaining days).
 * - If already expired: extend from now by `days` (default TRIAL_DAYS).
 * - forceExtend: always add `days` from max(now, expires_at) — used by "+30 hari" only.
 */
function allowDevice(id, days, forceExtend, unlimited) {
  const row = db.prepare(`SELECT * FROM devices WHERE device_id = ?`).get(id);
  if (!row) return null;
  const now = nowIso();
  let expiresAt = row.expires_at;
  let daysAdded = 0;
  if (unlimited) {
    expiresAt = UNLIMITED_EXPIRES;
    daysAdded = null;
  } else {
    const expired =
      isUnlimitedExpires(row.expires_at)
        ? false
        : new Date(row.expires_at).getTime() <= Date.now();
    if (forceExtend || expired) {
      const base =
        new Date(row.expires_at).getTime() > Date.now() &&
        !isUnlimitedExpires(row.expires_at)
          ? row.expires_at
          : now;
      expiresAt = addDays(base, days);
      daysAdded = days;
    }
  }
  db.prepare(
    `UPDATE devices SET status = 'active', expires_at = ?, last_check_at = ? WHERE device_id = ?`,
  ).run(expiresAt, now, id);
  const updated = db.prepare(`SELECT * FROM devices WHERE device_id = ?`).get(id);
  return { ...devicePayload(updated), daysAdded, ok: true };
}

app.post('/v1/admin/devices/:id/unblock', requireAdmin, (req, res) => {
  const id = req.params.id;
  const row = db.prepare(`SELECT * FROM devices WHERE device_id = ?`).get(id);
  if (!row) return res.status(404).json({ error: 'not_found' });

  const unlimited = Boolean(req.body?.unlimited);
  const expired =
    !isUnlimitedExpires(row.expires_at) &&
    new Date(row.expires_at).getTime() <= Date.now();
  const days = Math.min(
    3650,
    Math.max(1, Number(req.body?.days) || TRIAL_DAYS),
  );
  const result = allowDevice(id, days, false, unlimited);
  if (!result) return res.status(404).json({ error: 'not_found' });
  logEvent(
    'admin_unblock',
    id,
    unlimited
      ? 'unlimited'
      : expired && result.daysAdded
        ? `extended_${result.daysAdded}d`
        : 'resume_remaining',
  );
  pushLicenseToDevice(id, result);
  return res.json(result);
});

app.post('/v1/admin/devices/:id/allow', requireAdmin, (req, res) => {
  const id = req.params.id;
  const unlimited = Boolean(req.body?.unlimited);
  const days = Math.min(
    3650,
    Math.max(1, Number(req.body?.days) || TRIAL_DAYS),
  );
  const forceExtend = Boolean(req.body?.forceExtend) && !unlimited;
  const result = allowDevice(id, days, forceExtend, unlimited);
  if (!result) return res.status(404).json({ error: 'not_found' });
  logEvent('admin_allow', id, unlimited ? 'unlimited' : `${days}d`);
  pushLicenseToDevice(id, result);
  return res.json(result);
});

app.post('/v1/admin/keys/:code/revoke', requireAdmin, (req, res) => {
  const code = req.params.code;
  const info = db
    .prepare(
      `UPDATE keys SET status = 'revoked' WHERE key_code = ? AND status = 'unused'`,
    )
    .run(code);
  if (info.changes === 0) return res.status(404).json({ error: 'not_found' });
  logEvent('admin_revoke_key', null, code);
  return res.json({ ok: true });
});

app.get('/v1/admin/stats', requireAdmin, (req, res) => {
  const appId = normalizeAppId(req.query.app, { strict: true });
  if (!appId) {
    return res.status(400).json({ error: 'invalid_app', apps: APP_IDS });
  }
  const devices = db
    .prepare(`SELECT COUNT(*) AS c FROM devices WHERE app_id = ?`)
    .get(appId).c;
  const active = db
    .prepare(
      `SELECT COUNT(*) AS c FROM devices WHERE app_id = ? AND status = 'active' AND expires_at > ?`,
    )
    .get(appId, nowIso()).c;
  const unusedKeys = db
    .prepare(
      `SELECT COUNT(*) AS c FROM keys WHERE app_id = ? AND status = 'unused'`,
    )
    .get(appId).c;
  res.json({ devices, active, unusedKeys, trialDays: TRIAL_DAYS, appId, apps: APP_IDS });
});

app.get('/v1/admin/apps', requireAdmin, (_req, res) => {
  res.json({
    apps: [
      { id: 'filenest', label: 'FileNest' },
      { id: 'brilink', label: 'BRI Link' },
    ],
  });
});

app.get('/', (_req, res) => {
  res.redirect('/admin.html');
});

/** deviceId -> Set<WebSocket> — push channel for Block/Unblock (no phone polling). */
const socketsByDevice = new Map();

function pushLicenseToDevice(deviceId, payload) {
  const set = socketsByDevice.get(deviceId);
  if (!set || set.size === 0) return 0;
  const msg = JSON.stringify({
    type: 'license',
    status: payload.status,
    expiresAt: payload.expiresAt,
    serverTime: payload.serverTime || nowIso(),
    trialDays: payload.trialDays,
    daysAdded: payload.daysAdded,
    unlimited: Boolean(payload.unlimited),
  });
  let sent = 0;
  for (const ws of set) {
    if (ws.readyState === 1) {
      try {
        ws.send(msg);
        sent += 1;
      } catch (_) {
        /* ignore */
      }
    }
  }
  return sent;
}

const server = http.createServer(app);
const wss = new WebSocketServer({ server, path: '/v1/license/ws' });

wss.on('connection', (ws, req) => {
  let deviceId = '';
  try {
    const host = req.headers.host || 'localhost';
    const url = new URL(req.url || '/', `http://${host}`);
    deviceId = String(url.searchParams.get('deviceId') || '').trim();
  } catch (_) {
    deviceId = '';
  }
  if (!deviceId || deviceId.length < 8 || deviceId.length > 128) {
    ws.close(1008, 'invalid_device_id');
    return;
  }

  let set = socketsByDevice.get(deviceId);
  if (!set) {
    set = new Set();
    socketsByDevice.set(deviceId, set);
  }
  set.add(ws);

  // Immediate snapshot so a late-connecting app syncs without HTTP poll loops.
  const row = db.prepare(`SELECT * FROM devices WHERE device_id = ?`).get(deviceId);
  if (row) {
    try {
      ws.send(
        JSON.stringify({
          type: 'license',
          ...devicePayload(row),
        }),
      );
    } catch (_) {
      /* ignore */
    }
  }

  ws.on('close', () => {
    const s = socketsByDevice.get(deviceId);
    if (!s) return;
    s.delete(ws);
    if (s.size === 0) socketsByDevice.delete(deviceId);
  });

  ws.on('error', () => {
    /* close handler cleans up */
  });

  // Client may send ping JSON; reply pong to keep NAT paths warm.
  ws.on('message', (data) => {
    try {
      const text = String(data || '');
      if (text.includes('ping')) {
        ws.send(JSON.stringify({ type: 'pong', serverTime: nowIso() }));
      }
    } catch (_) {
      /* ignore */
    }
  });
});

server.listen(PORT, () => {
  // eslint-disable-next-line no-console
  console.log(`FileNest license server on :${PORT} (ws /v1/license/ws)`);
  if (ADMIN_PASSWORD === 'changeme') {
    // eslint-disable-next-line no-console
    console.warn('WARNING: ADMIN_PASSWORD is default "changeme" — change it.');
  }
});
