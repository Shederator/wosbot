# Continuous Integration

Frostguard builds on GitHub Actions from
[`.github/workflows/daily-windows-bundle.yml`](../.github/workflows/daily-windows-bundle.yml).
No manual activation step is needed — the workflow is live as soon as it lands on
`main`, and the first scheduled run happens at the next 03:17 UTC.

## When it runs

| Trigger | Purpose |
|---|---|
| `schedule` (03:17 UTC daily) | Publishes a nightly Windows bundle for testers |
| `pull_request` | Guards `pom.xml`, `src/`, `tools/`, `custom_tasks/`, `ci/`, `fg-watcher.bat` and `.gitattributes` |
| `push` to `ci/**` | Lets CI changes be iterated on a branch |
| `workflow_dispatch` | On-demand build from the Actions tab |

## What the pipeline does

1. Checks out the repository **with Git LFS**, then asserts that every LFS asset
   was really materialised. The check fails if `git lfs ls-files` returns nothing
   at all, if one of the four critical assets is no longer tracked, if a file is
   still a pointer stub, or if it is implausibly small. Without those guards the
   step could pass vacuously and ship a bundle that fails only on a user's PC.
2. Sets up **Temurin JDK 21** with a Maven dependency cache.
3. Installs `libtesseract` / `libleptonica`, which tess4j binds at runtime for
   the OCR regression tests. OpenCV needs no system package — the
   `org.openpnp:opencv` artifact ships the Linux native image.
4. Runs the verifier's own unit tests (`ci/test_verify_bundle.py`), so a
   verification script that can no longer fail cannot silently green-light a
   broken artifact.
5. Runs `mvn clean install -Djavafx.platform=win`. This **cross-builds the
   Windows desktop bundle from Linux** while still executing the full JUnit 5
   suite, including the vision and OCR saved-frame tests.
6. **Structurally verifies** the ZIP with [`verify_bundle.py`](verify_bundle.py):
   Windows JavaFX runtime present and no other platform's runtime leaking, the
   launcher and watcher JARs, bundled `adb`/OCR assets, template sprites,
   `custom_tasks/`, a floor on staged runtime JARs, and that **every
   `Class-Path` entry of the launcher manifest really exists** in the archive.
7. **Launch-smoke-tests** the extracted bundle with
   [`smoke_test_bundle.sh`](smoke_test_bundle.sh): resolves the real entry points
   off the real bundle classpath, checks `java -jar` resolves the manifest
   `Class-Path`, and boots the shaded Telegram watcher for real.
8. Uploads the bundle (version-tagged, no re-compression) and the Surefire
   reports, and writes a job summary with size, JAR count and test count.

## Why two verification layers

`verify_bundle.py` proves the right files are at the right paths. It cannot prove
they link together. A dependency dropped from a POM, a shaded JAR that lost a
transformer, or an incompatible library bump all produce a *structurally perfect*
bundle that dies with `NoClassDefFoundError` the first time a user runs it.
`smoke_test_bundle.sh` closes that gap by loading the classes for real.

Both layers were validated against a deliberately damaged bundle: removing
`lib/hibernate-core-*.jar` is caught by the manifest cross-check **and**
independently by the smoke test.

## Why `-Djavafx.platform=win`

JavaFX artifacts are platform-classified. Without this flag a Linux runner
resolves the `-linux` classifier and produces a bundle that cannot start on
Windows. The flag forces the `-win` classifier, and step 6 asserts the
substitution really took effect in both directions.

## Notes for maintainers

- Tests are **not** skipped. `OpenCvPatternLocator.loadNativeLibrary()` selects
  the native image per platform, so the vision suites run on Linux runners and on
  Windows developer machines alike.
- The bundle is ~220 MB, mostly the OpenCV and JavaFX runtimes. It is uploaded
  with `compression-level: 0` because a ZIP does not recompress usefully.
- `smoke_test_bundle.sh` unpacks over 400 MB. It extracts next to the ZIP rather
  than into `/tmp`, since `/tmp` is a small tmpfs on many machines. Override the
  location with `FROSTGUARD_SMOKE_TMPDIR`.
- Reproduce the whole pipeline locally on Linux or Windows with:

  ```sh
  mvn clean install -Djavafx.platform=win
  python3 ci/test_verify_bundle.py
  python3 ci/verify_bundle.py fg-app/target/frostguard-*-desktop-bundle.zip
  ci/smoke_test_bundle.sh fg-app/target/frostguard-*-desktop-bundle.zip
  ```
