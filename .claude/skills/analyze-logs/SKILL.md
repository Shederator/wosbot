---
name: analyze-logs
description: >
  Read Frostguard account logs and frostguard.log for one routine.
  Use when the user asks to analyze logs, read an account log, explain what
  a routine did, or runs /analyze-logs. Confirm the routine, profiles, day,
  and logs directory, then return a visit timeline.
user-invocable: true
---

# Analyze Frostguard logs

Read `.agents/skills/analyze-logs/SKILL.md` and follow it for the whole task. That file is the procedure.

This file is a normal file so a Windows checkout does not depend on a symlink. Git for Windows writes a committed symlink as text when `core.symlinks` is off.
