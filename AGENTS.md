# Repository Guidelines

## Project Structure & Module Organization

Frostguard is a Java 21 multi-module Maven project. The parent `pom.xml` defines shared versions and builds these modules in order: `fg-api`, `fg-data`, `fg-vision`, `fg-engine`, `fg-tasks`, `fg-watcher`, and `fg-app`. Source code follows standard Maven layout under each module's `src/main/java`; resources live in `src/main/resources`. JavaFX UI files and styles are in `fg-app/src/main/resources/layout` and `fg-app/src/main/resources/styles`. Image templates and native vision assets are in `fg-vision/src/main/resources/templates` and `fg-vision/src/main/resources/native`. Tests currently live mainly in `fg-engine/src/test` and `fg-tasks/src/test`.

## Build, Test, and Development Commands

- `mvn clean install` compiles all modules and runs JUnit 5 tests.
- `mvn clean install package` builds, tests, and packages the desktop app and distribution ZIP.
- `mvn -pl fg-engine test` runs tests for one module; add `-am` when dependencies must be built.
- `fg-build.bat` is the Windows packaging helper; it retries known transient packaging failures and validates the app JAR.
- `java -jar fg-app/target/frostguard-2.1.0.jar` runs the packaged desktop application after a successful build.

## Coding Style & Naming Conventions

Use Java 21 language features conservatively and keep package names under `dev.frostguard`. Follow the existing style: 4-space indentation in Java, braces on the same line, descriptive method names, and JUnit test methods written as behavior statements such as `rejectsMalformedPersistedReservationsConservatively`. Keep Maven XML indentation consistent with surrounding POMs. Do not commit generated `target/` output.

## Testing Guidelines

Tests use JUnit Jupiter via Maven Surefire. Name test classes `*Test` and place fixtures in the matching module under `src/test/resources` when image or OCR evidence is needed. Add focused regression tests for scheduler, vision, task, and parsing changes. Run at least the affected module's tests before opening a PR; run `mvn clean install` for cross-module changes.

## Commit & Pull Request Guidelines

Recent history uses concise, scoped messages such as `fix(research): handle completion and resource refill`, plus merge commits from feature/fix branches. Prefer `fix(area): summary` or `feat(area): summary` for new work. Pull requests should describe the behavior change, list tests run, link related issues, and include screenshots or sample logs for UI, emulator, or vision changes.

## Security & Configuration Tips

The app interacts with Android emulators through ADB and ships native OCR/vision assets. Avoid committing local profile data, credentials, emulator-specific paths, or private logs. Keep large binary/runtime changes intentional and verify Git LFS expectations before moving CI workflows.
