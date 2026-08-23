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
const crypto = require('crypto');
const express = require('express');
const cors = require('cors');
const rateLimit = require('express-rate-limit');
const Database = require('better-sqlite3');

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

const insertEvent = db.prepare(
  `INSERT INTO events (at, kind, device_id, detail) VALUES (?, ?, ?, ?)`,
);

function nowIso() {
  return new Date().toISOString();
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
  const expired = new Date(row.expires_at).getTime() <= Date.now();
  const blocked = row.status === 'blocked';
  let status = 'active';
  if (blocked) status = 'blocked';
  else if (expired) status = 'expired';
  return {
    status,
    expiresAt: row.expires_at,
    serverTime,
    trialDays: TRIAL_DAYS,
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
    logEvent('register_existing', deviceId, appVersion);
    return res.json(devicePayload(existing));
  }

  const created = nowIso();
  const expires = addDays(created, TRIAL_DAYS);
  db.prepare(
    `INSERT INTO devices (device_id, created_at, expires_at, last_check_at, status, app_version)
     VALUES (?, ?, ?, ?, 'active', ?)`,
  ).run(deviceId, created, expires, created, appVersion);
  logEvent('register_new', deviceId, `trial_${TRIAL_DAYS}d`);
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

  let device = db.prepare(`SELECT * FROM devices WHERE device_id = ?`).get(deviceId);
  const now = nowIso();
  if (!device) {
    const expires = addDays(now, keyRow.days);
    db.prepare(
      `INSERT INTO devices (device_id, created_at, expires_at, last_check_at, status)
       VALUES (?, ?, ?, ?, 'active')`,
    ).run(deviceId, now, expires, now);
  } else {
    if (device.status === 'blocked') {
      return res.status(403).json({ error: 'device_blocked' });
    }
    const base =
      new Date(device.expires_at).getTime() > Date.now()
        ? device.expires_at
        : now;
    const expires = addDays(base, keyRow.days);
    db.prepare(
      `UPDATE devices SET expires_at = ?, last_check_at = ?, status = 'active' WHERE device_id = ?`,
    ).run(expires, now, deviceId);
  }

  db.prepare(
    `UPDATE keys SET status = 'used', used_at = ?, used_by_device = ? WHERE key_code = ?`,
  ).run(now, deviceId, key);

  device = db.prepare(`SELECT * FROM devices WHERE device_id = ?`).get(deviceId);
  logEvent('activate_ok', deviceId, key);
  return res.json({
    ...devicePayload(device),
    daysAdded: keyRow.days,
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
  const days = Math.min(3650, Math.max(1, Number(req.body?.days) || TRIAL_DAYS));
  const count = Math.min(50, Math.max(1, Number(req.body?.count) || 1));
  const created = nowIso();
  const insert = db.prepare(
    `INSERT INTO keys (key_code, days, created_at, status) VALUES (?, ?, ?, 'unused')`,
  );
  const keys = [];
  const tx = db.transaction(() => {
    for (let i = 0; i < count; i++) {
      let code;
      for (let attempt = 0; attempt < 20; attempt++) {
        code = generateKeyCode();
        try {
          insert.run(code, days, created);
          keys.push({ key: code, days, createdAt: created });
          break;
        } catch (_) {
          /* collision */
        }
      }
    }
  });
  tx();
  logEvent('admin_generate', null, `${keys.length}x${days}d`);
  return res.json({ keys });
});

app.get('/v1/admin/keys', requireAdmin, (req, res) => {
  const status = String(req.query.status || '').trim();
  let rows;
  if (status) {
    rows = db
      .prepare(
        `SELECT * FROM keys WHERE status = ? ORDER BY created_at DESC LIMIT 200`,
      )
      .all(status);
  } else {
    rows = db
      .prepare(`SELECT * FROM keys ORDER BY created_at DESC LIMIT 200`)
      .all();
  }
  return res.json({ keys: rows });
});

app.get('/v1/admin/devices', requireAdmin, (_req, res) => {
  const rows = db
    .prepare(`SELECT * FROM devices ORDER BY created_at DESC LIMIT 500`)
    .all();
  return res.json({
    devices: rows.map((r) => ({ ...r, ...devicePayload(r) })),
  });
});

app.post('/v1/admin/devices/:id/block', requireAdmin, (req, res) => {
  const id = req.params.id;
  const info = db
    .prepare(`UPDATE devices SET status = 'blocked' WHERE device_id = ?`)
    .run(id);
  if (info.changes === 0) return res.status(404).json({ error: 'not_found' });
  logEvent('admin_block', id, null);
  return res.json({ ok: true });
});

app.post('/v1/admin/devices/:id/unblock', requireAdmin, (req, res) => {
  const id = req.params.id;
  const info = db
    .prepare(`UPDATE devices SET status = 'active' WHERE device_id = ?`)
    .run(id);
  if (info.changes === 0) return res.status(404).json({ error: 'not_found' });
  logEvent('admin_unblock', id, null);
  return res.json({ ok: true });
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

app.get('/v1/admin/stats', requireAdmin, (_req, res) => {
  const devices = db.prepare(`SELECT COUNT(*) AS c FROM devices`).get().c;
  const active = db
    .prepare(
      `SELECT COUNT(*) AS c FROM devices WHERE status = 'active' AND expires_at > ?`,
    )
    .get(nowIso()).c;
  const unusedKeys = db
    .prepare(`SELECT COUNT(*) AS c FROM keys WHERE status = 'unused'`)
    .get().c;
  res.json({ devices, active, unusedKeys, trialDays: TRIAL_DAYS });
});

app.get('/', (_req, res) => {
  res.redirect('/admin.html');
});

app.listen(PORT, () => {
  // eslint-disable-next-line no-console
  console.log(`FileNest license server on :${PORT}`);
  if (ADMIN_PASSWORD === 'changeme') {
    // eslint-disable-next-line no-console
    console.warn('WARNING: ADMIN_PASSWORD is default "changeme" — change it.');
  }
});
