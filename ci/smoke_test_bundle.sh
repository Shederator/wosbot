#!/usr/bin/env bash
#
# Launch-smoke-test the extracted desktop bundle.
#
# Structural verification (ci/verify_bundle.py) proves the right files are in the
# ZIP. It cannot prove they actually link together: a dependency dropped from a
# POM, a shaded JAR that lost a transformer, or an incompatible library upgrade
# all produce a structurally perfect bundle that dies with NoClassDefFoundError
# the first time a user runs it. This script resolves the real entry points from
# the real bundle classpath, so that class of breakage fails the build instead.
#
# The JavaFX runtime in a Windows bundle cannot start a UI on Linux, so we
# deliberately resolve classes without initialising them rather than booting the
# application. The watcher JAR is self-contained and IS executed for real.
#
# Usage: ci/smoke_test_bundle.sh <bundle.zip>

set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "usage: $0 <bundle.zip>" >&2
  exit 2
fi

bundle_zip="$(cd "$(dirname "$1")" && pwd)/$(basename "$1")"

# The bundle unpacks to well over 400 MB. On many machines (and in containers)
# /tmp is a small tmpfs, so extract next to the ZIP on real disk instead. Callers
# can override with FROSTGUARD_SMOKE_TMPDIR.
smoke_parent="${FROSTGUARD_SMOKE_TMPDIR:-$(dirname "${bundle_zip}")}"
mkdir -p "${smoke_parent}"
workdir="$(mktemp -d "${smoke_parent}/frostguard-smoke-XXXXXX")"
trap 'rm -rf "${workdir}"' EXIT

# -n: never prompt. A prompt (e.g. on a full disk) would otherwise hang the job.
unzip -qn "${bundle_zip}" -d "${workdir}"
cd "${workdir}"

app_jar="$(find . -maxdepth 1 -name 'frostguard-*.jar' | head -n 1)"
watcher_jar="$(find . -maxdepth 1 -name 'fg-watcher-*.jar' | head -n 1)"

if [[ -z "${app_jar}" || -z "${watcher_jar}" ]]; then
  echo "::error::Bundle is missing the app or watcher JAR."
  exit 1
fi

# ── 1. Resolve the classes the launcher needs, off the real bundle classpath ──
cat > Probe.java <<'PROBE'
public class Probe {
    public static void main(String[] args) throws Exception {
        String[] required = {
            // Application entry points.
            "dev.frostguard.app.bootstrap.Main",
            "dev.frostguard.app.bootstrap.FXApp",
            "dev.frostguard.app.bootstrap.HeadlessApp",
            "dev.frostguard.app.panel.launcher.LauncherLayoutController",
            // Cross-module wiring that the launcher touches during startup.
            "dev.frostguard.engine.service.AnalyticsService",
            "dev.frostguard.tasks.TaskRegistrations",
            "dev.frostguard.vision.logging.ProfileContextLogger",
            "dev.frostguard.vision.match.OpenCvPatternLocator",
            // Third-party runtimes that must be staged into lib/.
            "javafx.application.Application",
            "javafx.fxml.FXMLLoader",
            "org.opencv.core.Mat",
            "net.sourceforge.tess4j.Tesseract",
            "org.hibernate.SessionFactory",
            "com.fasterxml.jackson.databind.ObjectMapper",
            "ch.qos.logback.classic.LoggerContext",
        };
        ClassLoader loader = Probe.class.getClassLoader();
        for (String name : required) {
            // initialize=false: resolving is enough, and avoids static
            // initialisers that would need a display or an emulator.
            Class.forName(name, false, loader);
            System.out.println("  resolved " + name);
        }

        // The launcher manifest must name a main method that really exists.
        Class<?> main = Class.forName("dev.frostguard.app.bootstrap.Main", false, loader);
        main.getDeclaredMethod("main", String[].class);
        System.out.println("  Main.main(String[]) present");
    }
}
PROBE

echo "Resolving launcher entry points against the bundle classpath..."
javac -nowarn -cp "${app_jar}:lib/*" -d . Probe.java
java -cp "${app_jar}:lib/*:." Probe

# ── 2. The manifest Class-Path must be usable without an explicit -cp ──
# `java -jar` honours only the manifest, so this proves the Class-Path really
# resolves relative to the bundle root the way a user's double-click would.
echo "Checking the app JAR manifest Class-Path resolves via java -jar..."
manifest_probe_log="${workdir}/manifest-probe.log"
set +e
# The launcher ignores unknown flags and falls through to the JavaFX path, which
# has no display here and exits on its own. `timeout` is a safety net so the job
# can never hang if a future change makes it wait on something instead.
timeout 120s java -Djava.awt.headless=true -Duser.home="${workdir}/app-home" \
  -jar "${app_jar}" --frostguard-ci-smoke-test > "${manifest_probe_log}" 2>&1
set -e
if grep -q "NoClassDefFoundError\|ClassNotFoundException\|Could not find or load main class" \
    "${manifest_probe_log}"; then
  echo "::error::Launching the app JAR failed to resolve its classpath:"
  sed 's/^/    /' "${manifest_probe_log}" | head -n 30
  exit 1
fi
echo "  no classpath resolution errors from java -jar"

# ── 3. The watcher is platform-independent, so boot it for real ──
# It is a shaded uber-JAR, so this is the check that a lost shade transformer or
# a missing bundled dependency cannot hide. Note we assert on observable startup
# behaviour, not on the exit status: with no Telegram token configured the
# watcher correctly reports that and exits non-zero, which is not a build fault.
echo "Booting the Telegram watcher from the bundle..."
watcher_log="${workdir}/watcher.log"
watcher_home="${workdir}/watcher-home"
mkdir -p "${watcher_home}"
set +e
# -Duser.home: the watcher derives its config path from user.home, which the JVM
# reads from the OS account rather than $HOME. Redirect it so the smoke test
# never touches a real developer profile.
timeout 90s java -Duser.home="${watcher_home}" -jar "${watcher_jar}" \
  > "${watcher_log}" 2>&1
watcher_status=$?
set -e

if grep -q "NoClassDefFoundError\|ClassNotFoundException\|Could not find or load main class" \
    "${watcher_log}"; then
  echo "::error::The bundled watcher JAR is missing classes at runtime:"
  sed 's/^/    /' "${watcher_log}" | head -n 30
  exit 1
fi

# The banner is printed after the watcher has wired up logging and config, so it
# is proof the shaded JAR is internally complete.
if ! grep -q "Telegram Watcher" "${watcher_log}"; then
  echo "::error::The watcher produced no recognisable banner (exit ${watcher_status}):"
  sed 's/^/    /' "${watcher_log}" | head -n 30
  exit 1
fi

# It must reach one of two legitimate states: it created/read its config and
# stopped for a missing token, or it started polling until the timeout fired.
if ! grep -q "telegram-watcher.properties" "${watcher_log}" \
    && [[ "${watcher_status}" -ne 124 ]]; then
  echo "::error::The watcher never reached its configuration stage (exit ${watcher_status}):"
  sed 's/^/    /' "${watcher_log}" | head -n 30
  exit 1
fi

if [[ ! -f "${watcher_home}/.frostguard/telegram-watcher.properties" ]]; then
  echo "::error::The watcher did not create its config template under user.home."
  sed 's/^/    /' "${watcher_log}" | head -n 30
  exit 1
fi
echo "  watcher booted and wrote its config template"

echo "Bundle smoke test passed."
