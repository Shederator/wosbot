# MuMu Manager lifecycle compatibility

Frostguard prefers the structured MuMu Manager command family:

```text
MuMuManager.exe info -v <index>
MuMuManager.exe control -v <index> launch
MuMuManager.exe control -v <index> shutdown
```

The configured manager directory remains the installation's `nx_main` directory.
Only one canonical, non-negative numeric instance index is accepted. Commands
never target `all` or a comma-separated list.

## Compatibility decision

The [vendor reference](https://mumu.163.com/help/20240726/35047_1170006.html)
documents modern `info/control` commands and retains an older `api` family with
a deprecation warning. Capability detection uses a read-only `info` request;
there is no guessed version-number cutoff.

Only an explicit `unknown cmd: info`, `unknown command: info` or
`unrecognized command: info` response selects the legacy commands:

```text
MuMuManager.exe api -v <index> player_state
MuMuManager.exe api -v <index> launch_player
MuMuManager.exe api -v <index> shutdown_player
```

The detected family is retained for that backend instance. A failed or timed-out
modern action never triggers another action using the legacy family. Malformed,
empty, timed-out and ordinary failed probes do not establish lack of support.
An unrecognized older manager response fails conservatively and requires a
documented compatibility fixture before broadening detection.

Modern actions require exit zero and an explicit integer-zero `errcode` or
`error_code`; every supplied code must agree. State also verifies the returned
instance index, boolean process/Android flags, and known lifecycle state.
`start_finished` is running only when both flags are true and a supplied launch
error code is zero. Partial, contradictory or missing evidence is not confirmed
running. State parsing has an internal unknown outcome; the existing public
`isRunning` boolean still returns false for unknown/failure. It must not be used
as independent proof of a fully stopped host VM.

Legacy action exit zero alone does not establish acceptance. A negative
`player launch/shutdown: result=...` acknowledgement fails. Otherwise the client
checks the selected instance for an expected starting/running or stopping/stopped
transition with a bounded deadline. Missing or conflicting state markers remain
unknown. No action is retried by this compatibility layer.

Every manager process uses the existing bounded process runner: five seconds per
probe and 45 seconds per lifecycle action, with a separate five-second legacy
transition budget. Process termination can add its bounded cleanup grace. Stop
and launch failures propagate as exceptions; interrupted actions retain the
thread interrupt flag. Successful logs describe an accepted request, not a
completed boot or shutdown. Caller-owned startup deadlines and confirmed shutdown
remain separate concerns; see [issue #403](https://github.com/Shederator/wosbot/issues/403).

## Evidence and limits

- Automated coverage exercises modern/legacy command selection, structured and
  text state parsing, mismatched targets, missing/malformed/error responses,
  cancellation, timeout termination and failure propagation to backend callers.
  The full `mvnw.cmd package` reactor passed **1,207 tests** with no failures,
  errors or skipped tests, including 15 compatibility tests and five real
  subprocess tests. Verification ran on Temurin 25.0.3; a separate Java 21 run
  was not performed.
- Live local verification on MuMu **6.8.9.0**, Android **15**, observed accepted
  launch, running state, shutdown, manager process-stopped flags and relaunch
  through the compiled lifecycle implementation. The native bridge was disabled
  in the standalone harness to isolate manager behavior; no game input was sent.
- The installed manager's legacy `api` namespace remains callable. A rejection of
  `control ... launch_player` does not prove `api ... launch_player` unsupported:
  those are different command families. Legacy log output was insufficient for
  trustworthy state confirmation in this local check.
- Older MuMu binaries were not installed or executed. Their fallback is based on
  the vendor command contract and synthetic regression responses, not a live
  cross-version certification. Additional localized/help output formats remain
  unsupported until evidence justifies them.
- This change neither switches screenshot/input backends nor changes ADB-server
  ownership. It implements the lifecycle portion of
  [issue #114](https://github.com/Shederator/wosbot/issues/114); broader output
  retention and independent host-stop confirmation are not claimed complete.
