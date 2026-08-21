# Frostguard Agent Core Directive

You are an autonomous coding agent for the Frostguard project (Java 21 / Maven). You must strictly adhere to the engineering standards, architecture bounds, and documentation discovered within this repository.

## 1. Absolute Rule Enforcement
- Before writing, modifying, or planning any code changes, you MUST read and follow the guidelines in `docs/architecture.md`, `docs/windows.md`, and any relevant domain files.
- You must strictly comply with the shared engineering contract (e.g., matching the 4-space indentation, using Java 21 conservatively, keeping code comments/logs in English, and following the downward dependency direction).
- NEVER hallucinate, ignore, or bypass constraints established in the project's markdown files.

## 2. Technical Search Constraints (Anti-Crash)
- To prevent VS Code extension tool drops (`Had to drop tools due to limit constraints`) and WebSocket timeouts, you are FORBIDDEN from running a global `grep_search` across the entire repository.
- Scope all initial code discoveries using targeted `file_search` at the specific module level based on the Project Architecture Map below.
- Do not attempt to index or scan the compiled `target/` directories or the local operational workspace folder `.frostguard-dev/`.

## 3. Project Architecture Map (Where to Find Things)
Use this map to target your file searches precisely. Do not leak logic across boundaries:
- `modules/api` (`frostguard-api`): Shared cross-module contracts, domain values, and enums (e.g., `TpDailyTaskEnum`, `PointData`). No service or repository code here.
- `modules/persistence` (`frostguard-persistence`): SQLite, Hibernate entities, and repositories (`ProfileRepository`, `ConfigRepository`).
- `modules/vision` (`frostguard-vision`): Low-level image primitives, OCR executors, OpenCV pattern matching, and raw PNG templates.
- `modules/automation` (`frostguard-automation`): Core engine, emulator controller (ADB/inputs), scheduling (`ScheduleService`), and reusable navigation helpers.
- `modules/tasks` (`frostguard-tasks`): Game-specific automation routines (e.g., `alliance`, `city`, `dailies`). Extended from `DelayedTask`.
- `modules/desktop` (`frostguard-desktop`): JavaFX UI controllers, screens, FXML, and application entry points.
- `modules/update` (`frostguard-update`): Release manifest parsing, update trust policy, and Ed25519 signature verification.

## 4. Media & Asset Handling Rules
- **Binary Assets Only**: Images, template sprites, and UI screenshots must be treated strictly as binary assets.
- **No Text Leaks**: You are strictly FORBIDDEN from outputting raw image data, Base64 strings, or pixel arrays as text into the chat, logs, or source code.
- **Reference by Enum/Path**: Always reference visual assets via `TemplatesEnum` or their respective classpath resource paths under `modules/vision/src/main/resources/templates`.

## 5. Local Operator Overrides
- Check for the existence of `AGENTS.local.md` at the repository root before planning any work. 
- If `AGENTS.local.md` is present, you MUST read it completely at the very beginning of the session and prioritize its specific operational requirements and operator goals above all else.
