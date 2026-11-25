# MuMu screen capture

MuMu on Windows supports an optional **MuMu IPC (experimental)** screenshot
backend. In the emulator configuration page, select MuMu as the active emulator,
then choose **Capture method** beneath the emulator table. **ADB (default)**
preserves the existing screenshot path. Other emulators and Linux use ADB;
native MuMu IPC requires the Windows renderer DLL supplied with MuMu.

The preference is saved globally and takes effect on the next bot start. Stop
the bot before changing it, then start it again. After updating this source
checkout, restart the desktop application with `./mvnw javafx:run` (Windows:
`.\mvnw.cmd javafx:run`) to load the new UI and code. Installed releases need an
updated application containing this feature.

IPC screenshot selection is independent of touch selection. ADB remains the
default for input and handles Back, text entry and other device commands.
The bot discovers MuMu's renderer DLL from the configured emulator executable
and runs native capture in a supervised helper process. Unavailable or failed
native capture falls back to the selected instance's ADB screenshot path.
Cancellation stops capture instead of starting another fallback request.

## Optional native touch

For taps and swipes, enable **Use MuMu IPC for taps and swipes (experimental)**
beneath the capture dropdown, then stop/start the bot. The checkbox defaults to
off and is available for MuMu on Windows. It can be enabled with either capture
method. It reuses the same per-instance helper as native screenshots.

Initial support is single-contact gestures on portrait display 0. Taps hold for
40 ms; swipes interpolate over the requested duration (300 ms when unspecified),
up to 10 seconds. The helper checks geometry before input and attempts contact
release even when a native gesture fails. Landscape/native rotation support,
multi-touch and specialized continuous scrcpy steering are outside this change.

The runtime logs `Touch backend for instance <index>: mumu_ipc (nativeRequested=true)`
when it acknowledges native input. `adb` means fallback. Missing DLLs, symbols,
or startup failures permit ADB fallback before any gesture command is sent.
Once a gesture is sent, a timeout, failed response or disconnect is an unknown
outcome: the bot reports failure without replaying that gesture through ADB.
Acknowledgement proves the API completed, not that the intended game action did.

For the first account smoke test, keep one profile/instance active. Confirm a
benign navigation tap reaches the intended control, verify a scroll's direction
and duration, then stop/restart. Check the resulting screen after any uncertain
input. A native crash or forced worker termination can prevent contact release;
stop and inspect before retrying rather than assuming no touch occurred.

The binding follows the [vendor SDK header mirrored by EmulatorExtras](https://github.com/MaaXYZ/EmulatorExtras/blob/main/Mumu/external_renderer_ipc/external_renderer_ipc.h).
Pass normalized framebuffer coordinates directly: the operated SDK applies its
own rotation. An extra application-side rotation acknowledged taps but did not
deliver valid positions. Live Android input events and navigation to Chief
Profile and Settings confirmed direct coordinates on the tested 720×1280 MuMu
instance. Other SDK versions and landscape behavior require separate checks.

## Multiple emulators

Each MuMu instance index gets its own helper process, native connection, socket
and command lock. Capture and touch serialize within that instance; other
instances use independent locks. Set **Max Concurrent Instances** to 2 or more,
and configure profiles for distinct MuMu instance indices. Two profiles targeting
one emulator do not become independent devices.

Synthetic simultaneous-worker tests verify command isolation; they do not prove
the vendor renderer or the host can sustain multiple live instances. First smoke
test one instance, then two, checking that each profile acts on its own emulator
and the backend logs show the correct indices. CPU, RAM and GPU capacity still
limit useful concurrency. Stop/restart should release and recreate each helper.

## Account smoke test

1. Start with one enabled profile and one running MuMu instance, using the
   project's supported game resolution and existing routine settings.
2. Select **MuMu IPC (experimental)** and start the bot.
3. In the selected workspace's `logs/frostguard.log`, check for
   `Screenshot preference: MUMU_NEMU_IPC` and then
   `Screenshot backend for instance <index>: mumu_ipc (requested=MUMU_NEMU_IPC)`.
   A helper connection alone does not establish that the bot used an IPC frame.
   A backend of `adb` with that preference means fallback is active.
4. Confirm initialization identifies the expected character, navigation works,
   and a routine you have chosen reports the expected outcome. Review local
   diagnostic captures if OCR or navigation fails; a successful screenshot alone
   does not verify a routine's completion.
5. Stop the bot with the emulator left open, then start again. Confirm capture
   reconnects and the bot does not accumulate helper processes. Switch back to
   **ADB (default)** and restart the bot to compare the same routine.

Runtime evidence stays in the selected workspace. Redact identifying information
before sharing screenshots or logs. A full Maven package verifies the source
reactor; installed-runtime helper launch and longer account runs require their
own verification.

## Performance and scope

Native frame acquisition is faster on the measured MuMu instance, but it does not
remove game animations or prove routine readiness. Compare the same task and
game state with ADB capture/input, IPC capture/ADB input, then IPC capture/input.
Gameplay waits, OCR, completion accounting, startup policy and emulator launch
compatibility are separate changes and are not included in this feature.
