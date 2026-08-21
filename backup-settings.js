#!/usr/bin/env node
/**
 * Bearguard settings backup.
 *
 * Observed live after losing his configuration twice in one evening: "we can't have this
 * keep happening."
 *
 * Why it happens: Bearguard keeps SQLite in WAL mode, so recent writes live in database.db-wal
 * until a checkpoint folds them into database.db. Killing the process hard (which a rebuild
 * cycle does constantly) can leave that WAL unmerged, and the settings written since the last
 * checkpoint are effectively gone. The 4MB WAL sitting next to a 151KB database was the tell.
 *
 * What this does, in order:
 *   1. Checkpoints the WAL into the main database, so nothing is stranded.
 *   2. Copies the whole database to a timestamped file under settings-backups/.
 *   3. Writes a plain-text dump of every config row, so a wipe can be repaired key-by-key
 *      without swapping the entire database and losing whatever came after.
 *   4. Prunes to the newest RETAIN copies.
 *
 * Safe to run while Bearguard is up: the checkpoint is a normal SQLite operation and the copy is
 * taken through the sqlite3 backup API rather than a raw file copy, so it cannot catch a
 * half-written page.
 *
 * Usage:  node backup-settings.js
 */

'use strict';

const fs = require('fs');
const path = require('path');
const { execFileSync } = require('child_process');

const ROOT = __dirname;
const DB = path.join(ROOT, 'database.db');
const OUT = path.join(ROOT, 'settings-backups');
const RETAIN = 30;

const LOG_FILE = path.join(ROOT, 'settings-backup.log');

/**
 * Logs to stdout AND to its own file.
 *
 * "just make sure seriously next time you fucking get a backup log." The first
 * scheduled run failed silently because a bad path made the guard clause return before anything
 * ran, and nothing anywhere recorded that. A dedicated log means the backup's own history does
 * not depend on the supervisor's output being captured.
 */
function log(msg) {
  const line = `[${new Date().toISOString()}] ${msg}`;
  console.log(line);
  try {
    fs.appendFileSync(LOG_FILE, line + '\n');
  } catch {
    // Never let logging failure stop a backup.
  }
}

function stamp() {
  return new Date().toISOString().replace(/[:.]/g, '-').slice(0, 19);
}

function main() {
  if (!fs.existsSync(DB)) {
    log(`ERROR: no database at ${DB}`);
    process.exit(1);
  }
  fs.mkdirSync(OUT, { recursive: true });

  const when = stamp();
  const dbCopy = path.join(OUT, `database-${when}.db`);
  const dump = path.join(OUT, `config-${when}.txt`);

  // A tiny Python helper does the checkpoint, the backup-API copy and the dump in one
  // connection. Python ships with the SQLite bindings already, and this avoids depending on a
  // sqlite3 CLI being on PATH.
  const script = `
import sqlite3, sys
src = sqlite3.connect(r"${DB.replace(/\\/g, '\\\\')}")
src.execute("PRAGMA wal_checkpoint(TRUNCATE)")
src.commit()

dst = sqlite3.connect(r"${dbCopy.replace(/\\/g, '\\\\')}")
src.backup(dst)
dst.close()

cur = src.cursor()
cur.execute("SELECT profile_id, tp_config_id, config_key, value FROM config ORDER BY profile_id, config_key")
rows = cur.fetchall()
with open(r"${dump.replace(/\\/g, '\\\\')}", "w", encoding="utf-8") as fh:
    for profile_id, tp, key, value in rows:
        fh.write(f"{profile_id}\\t{tp}\\t{key}\\t{value}\\n")
print(len(rows))
src.close()
`;

  let count;
  try {
    count = execFileSync('python3', ['-c', script], { encoding: 'utf-8' }).trim();
  } catch (e) {
    log(`ERROR: backup failed: ${e.message}`);
    process.exit(1);
  }

  log(`Checkpointed WAL and backed up ${count} config rows -> ${path.basename(dbCopy)}`);

  // Prune oldest, keeping database/dump pairs together.
  for (const prefix of ['database-', 'config-']) {
    const files = fs.readdirSync(OUT)
      .filter(f => f.startsWith(prefix))
      .sort()
      .reverse();
    for (const stale of files.slice(RETAIN)) {
      fs.unlinkSync(path.join(OUT, stale));
    }
  }
}

main();
