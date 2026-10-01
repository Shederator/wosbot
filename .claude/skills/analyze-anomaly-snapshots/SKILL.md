---
name: analyze-anomaly-snapshots
description: >
  Dump a remote Frostguard workspace's logs and database, list diagnostic
  snapshot folders, then analyze the one the user picks. Use when the user
  asks to analyze anomaly snapshots, dump bot logs, review logs/snapshot,
  or runs /analyze-anomaly-snapshots. Do not start log analysis until they
  choose a snapshot folder.
user-invocable: true
---

# Analyze anomaly snapshots

Read `.agents/skills/analyze-anomaly-snapshots/SKILL.md` and follow it for the whole task. That file is the procedure.

This file is a normal file so a Windows checkout does not depend on a symlink. Git for Windows writes a committed symlink as text when `core.symlinks` is off.
