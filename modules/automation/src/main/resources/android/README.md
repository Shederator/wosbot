# Android touch transport

`scrcpy-server-v4.1` is the unmodified official scrcpy 4.1 Android server:
https://github.com/Genymobile/scrcpy/releases/tag/v4.1

SHA-256: `deacb991ed2509715160ffdc7907e47b4160eb30d1566217e9047fd5b8850cae`.
The runtime verifies this checksum before upload or launch.

Copyright (C) 2018 Genymobile; Copyright (C) 2018-2026 Romain Vimont.
Distributed under Apache License 2.0, reproduced in `scrcpy-LICENSE`.
Source: https://github.com/Genymobile/scrcpy/tree/v4.1/server
Protocol: https://github.com/Genymobile/scrcpy/blob/v4.1/doc/develop.md

Frostguard's Java client is original code. It runs the server only for bounded
Android touch sessions, with video, audio, clipboard synchronization, and power
changes disabled. Each session owns its socket forward, Android files, and
PID/start-time identity. No scrcpy desktop client is required.
