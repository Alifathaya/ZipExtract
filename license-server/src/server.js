'use strict';

/**
 * BRI-Link license server — API + admin UI.
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
const multer = require('multer');

const PORT = Number(process.env.PORT || 8787);
const ADMIN_PASSWORD = process.env.ADMIN_PASSWORD || 'changeme';
const TRIAL_DAYS = Number(process.env.TRIAL_DAYS || 30);
const DATA_DIR = process.env.DATA_DIR || path.join(__dirname, '..', 'data');
/** Stored APK updates — keep at most MAX_UPDATE_APKS per audience. */
const UPDATES_DIR = process.env.UPDATES_DIR || path.join(DATA_DIR, 'apk-updates');
const MAX_UPDATE_APKS = 3;

fs.mkdirSync(DATA_DIR, { recursive: true });
fs.mkdirSync(UPDATES_DIR, { recursive: true });
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

CREATE TABLE IF NOT EXISTS update_announcements (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  version TEXT NOT NULL,
  message TEXT NOT NULL,
  url TEXT NOT NULL,
  audience TEXT NOT NULL DEFAULT 'all',
  created_at TEXT NOT NULL,
  filename TEXT
);
`);

try {
  db.exec(`ALTER TABLE keys ADD COLUMN app TEXT NOT NULL DEFAULT 'any'`);
} catch (_) {
  /* column already exists */
}
db.prepare(`UPDATE keys SET app = 'any' WHERE app IS NOT NULL AND app != 'any'`).run();

try {
  db.exec(`ALTER TABLE update_announcements ADD COLUMN filename TEXT`);
} catch (_) {
  /* column already exists */
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

/** Audience filter: all | filenest (fn_) | brilink (bl_). */
function audienceMatches(audience, deviceId) {
  const id = String(deviceId || '');
  const a = String(audience || 'all');
  if (a === 'filenest') return id.startsWith('fn_');
  if (a === 'brilink') return id.startsWith('bl_');
  return true;
}

function updatePayload(row) {
  if (!row) return null;
  // Clients always download via /v1/updates/latest — never expose storage paths.
  return {
    version: row.version,
    message: row.message,
    url: publicLatestUpdatePath(row.audience),
    createdAt: row.created_at,
    audience: row.audience,
    filename: row.filename || null,
  };
}

function publicLatestUpdatePath(audience) {
  const app = audience === 'brilink' ? 'brilink' : 'filenest';
  return `/v1/updates/latest?app=${app}`;
}

function safeApkAudience(audience) {
  if (audience === 'brilink') return 'brilink';
  if (audience === 'all') return 'all';
  return 'filenest';
}

function listApkFiles(audienceFilter) {
  let files = [];
  try {
    files = fs.readdirSync(UPDATES_DIR).filter((f) => f.toLowerCase().endsWith('.apk'));
  } catch (_) {
    return [];
  }
  const out = [];
  for (const name of files) {
    const full = path.join(UPDATES_DIR, name);
    let st;
    try {
      st = fs.statSync(full);
    } catch (_) {
      continue;
    }
    // Filename: {audience}-{version}-{timestamp}.apk
    const m = /^([a-z]+)-(.+)-(\d+)\.apk$/i.exec(name);
    const audience = m ? m[1].toLowerCase() : 'filenest';
    const version = m ? m[2] : '';
    if (audienceFilter && audienceFilter !== 'all') {
      if (audience !== audienceFilter && audience !== 'all') continue;
    }
    out.push({
      filename: name,
      audience,
      version,
      size: st.size,
      mtimeMs: st.mtimeMs,
      createdAt: new Date(st.mtimeMs).toISOString(),
    });
  }
  out.sort((a, b) => b.mtimeMs - a.mtimeMs);
  return out;
}

/** Keep only the newest MAX_UPDATE_APKS files for this audience (and shared "all"). */
function pruneUpdateApks(audience) {
  const target = safeApkAudience(audience);
  // Prune only files matching this audience tag (not cross-delete other apps).
  const own = listApkFiles(null).filter((f) => f.audience === target);
  for (const old of own.slice(MAX_UPDATE_APKS)) {
    try {
      fs.unlinkSync(path.join(UPDATES_DIR, old.filename));
    } catch (_) {
      /* ignore */
    }
    try {
      db.prepare(`UPDATE update_announcements SET filename = NULL WHERE filename = ?`).run(
        old.filename,
      );
    } catch (_) {
      /* ignore */
    }
  }
  return listApkFiles(target);
}

function resolveLatestApk(appId) {
  const audience = appId === 'brilink' ? 'brilink' : 'filenest';
  const files = listApkFiles(null).filter(
    (f) => f.audience === audience || f.audience === 'all',
  );
  return files[0] || null;
}

function saveUploadedApk(file, audience, version) {
  const safeAudience = safeApkAudience(audience);
  const safeVersion = String(version || '0')
    .replace(/[^0-9A-Za-z._-]/g, '_')
    .slice(0, 32);
  const stamp = Date.now();
  const filename = `${safeAudience}-${safeVersion}-${stamp}.apk`;
  const dest = path.join(UPDATES_DIR, filename);
  fs.renameSync(file.path, dest);
  pruneUpdateApks(safeAudience);
  return filename;
}

/** Most recent announcement that matches this device's app prefix. */
function latestUpdateForDevice(deviceId) {
  const rows = db
    .prepare(
      `SELECT * FROM update_announcements ORDER BY id DESC LIMIT 40`,
    )
    .all();
  for (const row of rows) {
    if (audienceMatches(row.audience, deviceId)) {
      return updatePayload(row);
    }
  }
  return null;
}

function devicePayload(row) {
  const serverTime = nowIso();
  const unlimited = isUnlimitedExpires(row.expires_at);
  const expired = !unlimited && new Date(row.expires_at).getTime() <= Date.now();
  const blocked = row.status === 'blocked';
  let status = 'active';
  if (blocked) status = 'blocked';
  else if (expired) status = 'expired';
  const payload = {
    status,
    expiresAt: row.expires_at,
    serverTime,
    trialDays: TRIAL_DAYS,
    unlimited,
  };
  const update = latestUpdateForDevice(row.device_id);
  if (update) payload.update = update;
  return payload;
}

const app = express();
app.set('trust proxy', 1);
app.use(cors());
app.use(express.json({ limit: '32kb' }));
app.use(express.static(path.join(__dirname, '..', 'public')));

const apkUpload = multer({
  dest: path.join(UPDATES_DIR, '.tmp'),
  limits: { fileSize: 80 * 1024 * 1024, files: 1 },
  fileFilter: (_req, file, cb) => {
    const name = String(file.originalname || '').toLowerCase();
    const ok =
      name.endsWith('.apk') ||
      file.mimetype === 'application/vnd.android.package-archive' ||
      file.mimetype === 'application/octet-stream';
    cb(ok ? null : new Error('apk_only'), ok);
  },
});
fs.mkdirSync(path.join(UPDATES_DIR, '.tmp'), { recursive: true });

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
  const appVersion = String(req.body?.appVersion || '').trim() || null;
  if (!deviceId) {
    return res.status(400).json({ error: 'invalid_device_id' });
  }
  const row = db.prepare(`SELECT * FROM devices WHERE device_id = ?`).get(deviceId);
  if (!row) {
    return res.status(404).json({ error: 'unknown_device', status: 'unknown' });
  }
  db.prepare(
    `UPDATE devices SET last_check_at = ?, app_version = COALESCE(?, app_version) WHERE device_id = ?`,
  ).run(nowIso(), appVersion, deviceId);
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
  const unlimitedKey = Number(keyRow.days) === UNLIMITED_KEY_DAYS;
  if (!device) {
    const expires = unlimitedKey ? UNLIMITED_EXPIRES : addDays(now, keyRow.days);
    db.prepare(
      `INSERT INTO devices (device_id, created_at, expires_at, last_check_at, status)
       VALUES (?, ?, ?, ?, 'active')`,
    ).run(deviceId, now, expires, now);
  } else {
    if (device.status === 'blocked') {
      return res.status(403).json({ error: 'device_blocked' });
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
      `UPDATE devices SET expires_at = ?, last_check_at = ?, status = 'active' WHERE device_id = ?`,
    ).run(expires, now, deviceId);
  }

  db.prepare(
    `UPDATE keys SET status = 'used', used_at = ?, used_by_device = ? WHERE key_code = ?`,
  ).run(now, deviceId, key);

  device = db.prepare(`SELECT * FROM devices WHERE device_id = ?`).get(deviceId);
  logEvent('activate_ok', deviceId, unlimitedKey ? 'unlimited' : key);
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
  const unlimited = Boolean(req.body?.unlimited) || Number(req.body?.days) === 0;
  const days = unlimited
    ? UNLIMITED_KEY_DAYS
    : Math.min(3650, Math.max(1, Number(req.body?.days) || TRIAL_DAYS));
  const count = Math.min(50, Math.max(1, Number(req.body?.count) || 1));
  const created = nowIso();
  const insert = db.prepare(
    `INSERT INTO keys (key_code, days, created_at, status, app) VALUES (?, ?, ?, 'unused', 'any')`,
  );
  const keys = [];
  const tx = db.transaction(() => {
    for (let i = 0; i < count; i++) {
      let code;
      for (let attempt = 0; attempt < 20; attempt++) {
        code = generateKeyCode();
        try {
          insert.run(code, days, created);
          keys.push({
            key: code,
            days,
            unlimited,
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
    unlimited ? `${keys.length}x_unlimited` : `${keys.length}x${days}d`,
  );
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
    .prepare(
      `SELECT * FROM devices ORDER BY COALESCE(last_check_at, created_at) DESC LIMIT 500`,
    )
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

/** Set admin label / keterangan for a device (e.g. "Pak Dwi"). */
app.post('/v1/admin/devices/:id/note', requireAdmin, (req, res) => {
  const id = String(req.params.id || '').trim();
  if (!id) return res.status(400).json({ error: 'invalid_device_id' });
  const note = String(req.body?.note ?? '')
    .trim()
    .slice(0, 120);
  const info = db
    .prepare(`UPDATE devices SET note = ? WHERE device_id = ?`)
    .run(note || null, id);
  if (info.changes === 0) return res.status(404).json({ error: 'not_found' });
  logEvent('admin_note', id, note || '(cleared)');
  const row = db.prepare(`SELECT * FROM devices WHERE device_id = ?`).get(id);
  return res.json({ ok: true, device_id: id, note: row.note || null, ...devicePayload(row) });
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

app.delete('/v1/admin/keys/:code', requireAdmin, (req, res) => {
  const code = String(req.params.code || '').trim();
  if (!code || code.length !== 12) {
    return res.status(400).json({ error: 'invalid_key' });
  }
  const info = db.prepare(`DELETE FROM keys WHERE key_code = ?`).run(code);
  if (info.changes === 0) return res.status(404).json({ error: 'not_found' });
  logEvent('admin_delete_key', null, code);
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

/** Publish an in-app update announcement (WS push + attach on check/register). */
app.post(
  '/v1/admin/updates/broadcast',
  requireAdmin,
  (req, res, next) => {
    apkUpload.single('apk')(req, res, (err) => {
      if (err) {
        return res.status(400).json({ error: err.message || 'upload_failed' });
      }
      next();
    });
  },
  (req, res) => {
    const version = String(req.body?.version || '').trim().slice(0, 32);
    const message = String(req.body?.message || '').trim().slice(0, 2000);
    const audienceRaw = String(req.body?.audience || 'all').trim().toLowerCase();
    const audience = ['all', 'filenest', 'brilink'].includes(audienceRaw)
      ? audienceRaw
      : null;
    if (!version || !message || !audience) {
      if (req.file?.path) {
        try {
          fs.unlinkSync(req.file.path);
        } catch (_) {
          /* ignore */
        }
      }
      return res.status(400).json({ error: 'invalid_request' });
    }
    if (!req.file) {
      return res.status(400).json({ error: 'apk_required' });
    }

    let filename;
    try {
      filename = saveUploadedApk(req.file, audience, version);
    } catch (e) {
      return res.status(500).json({ error: 'save_failed', detail: String(e.message || e) });
    }

    const created = nowIso();
    const url = publicLatestUpdatePath(audience);
    const info = db
      .prepare(
        `INSERT INTO update_announcements (version, message, url, audience, created_at, filename)
         VALUES (?, ?, ?, ?, ?, ?)`,
      )
      .run(version, message, url, audience, created, filename);
    const update = {
      id: Number(info.lastInsertRowid),
      version,
      message,
      url,
      createdAt: created,
      audience,
      filename,
    };
    const pushed = broadcastUpdate(update);
    logEvent(
      'admin_update_broadcast',
      null,
      `${audience}:${version}:${filename}:pushed_${pushed}`,
    );
    return res.json({
      ok: true,
      update,
      pushed,
      files: listApkFiles(audience === 'all' ? null : audience).slice(0, MAX_UPDATE_APKS),
    });
  },
);

/** List stored APK backups (max 3 per audience) + recent announcements. */
app.get('/v1/admin/updates/latest', requireAdmin, (req, res) => {
  const audience = String(req.query.audience || '').trim().toLowerCase();
  let rows = db
    .prepare(`SELECT * FROM update_announcements ORDER BY id DESC LIMIT 20`)
    .all();
  if (audience && ['all', 'filenest', 'brilink'].includes(audience)) {
    rows = rows.filter((r) => r.audience === audience || r.audience === 'all');
  }
  return res.json({
    updates: rows.map((r) => ({
      id: r.id,
      ...updatePayload(r),
    })),
    files: listApkFiles(
      audience && ['filenest', 'brilink', 'all'].includes(audience) ? audience : null,
    ).slice(0, 12),
    maxFiles: MAX_UPDATE_APKS,
    updatesDir: 'apk-updates',
  });
});

/**
 * Public download — always serves the newest APK for the app.
 * Clients open this URL without displaying it to the user.
 */
app.get('/v1/updates/latest', (req, res) => {
  const appId = String(req.query.app || 'filenest').trim().toLowerCase();
  const latest = resolveLatestApk(appId);
  if (!latest) {
    return res.status(404).json({ error: 'no_update' });
  }
  const full = path.join(UPDATES_DIR, latest.filename);
  if (!fs.existsSync(full)) {
    return res.status(404).json({ error: 'file_missing' });
  }
  const downloadName = `FileNest-${latest.version || 'update'}.apk`;
  res.setHeader('Content-Type', 'application/vnd.android.package-archive');
  res.setHeader(
    'Content-Disposition',
    `attachment; filename="${downloadName.replace(/"/g, '')}"`,
  );
  res.setHeader('Cache-Control', 'no-store');
  return res.sendFile(full);
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
    update: payload.update || undefined,
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

function broadcastUpdate(update) {
  const msg = JSON.stringify({
    type: 'update',
    version: update.version,
    message: update.message,
    url: update.url || publicLatestUpdatePath(update.audience),
    createdAt: update.createdAt,
    audience: update.audience,
  });
  let sent = 0;
  for (const [deviceId, set] of socketsByDevice.entries()) {
    if (!audienceMatches(update.audience, deviceId)) continue;
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
      const payload = devicePayload(row);
      ws.send(
        JSON.stringify({
          type: 'license',
          ...payload,
        }),
      );
    } catch (_) {
      /* ignore */
    }
  } else {
    const update = latestUpdateForDevice(deviceId);
    if (update) {
      try {
        ws.send(JSON.stringify({ type: 'update', ...update }));
      } catch (_) {
        /* ignore */
      }
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
  console.log(`BRI-Link license server on :${PORT} (ws /v1/license/ws)`);
  if (ADMIN_PASSWORD === 'changeme') {
    // eslint-disable-next-line no-console
    console.warn('WARNING: ADMIN_PASSWORD is default "changeme" — change it.');
  }
});
