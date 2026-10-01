---
name: emulator-snapshot
description: >
  Capture a live emulator PNG over SSH or on localhost with adb screencap.
  Use when the user asks for an emulator snapshot, ADB capture, screencap,
  live frame, or runs /emulator-snapshot. The user must name a host
  (user@host or localhost). Confirm the Frostguard profile, then save the PNG.
user-invocable: true
---

# Emulator snapshot

Read `.agents/skills/emulator-snapshot/SKILL.md` and follow it for the whole task. That file is the procedure.

This file is a normal file so a Windows checkout does not depend on a symlink. Git for Windows writes a committed symlink as text when `core.symlinks` is off.
