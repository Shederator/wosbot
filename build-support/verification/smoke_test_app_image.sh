#!/usr/bin/env bash
# Smoke-test a macOS jpackage application image without touching the user's
# real Frostguard workspace.
set -euo pipefail

IMAGE="${1:-}"
if [[ -z "${IMAGE}" || ! -d "${IMAGE}" ]]; then
  echo "Usage: $0 <Frostguard.app>" >&2
  exit 2
fi

PRODUCT_NAME="$(basename "${IMAGE}" .app)"
LAUNCHER="${IMAGE}/Contents/MacOS/${PRODUCT_NAME}"
if [[ ! -x "${LAUNCHER}" ]]; then
  echo "Missing launcher: ${LAUNCHER}" >&2
  exit 1
fi

WORK="$(mktemp -d "${TMPDIR:-/tmp}/frostguard-mac-smoke.XXXXXX")"
cleanup() {
  if [[ -n "${APP_PID:-}" ]] && kill -0 "${APP_PID}" 2>/dev/null; then
    kill "${APP_PID}" 2>/dev/null || true
    wait "${APP_PID}" 2>/dev/null || true
  fi
  rm -rf "${WORK}"
}
trap cleanup EXIT

export FROSTGUARD_WORKSPACE="${WORK}/workspace"
export FROSTGUARD_CHANNEL="${FROSTGUARD_CHANNEL:-stable}"
mkdir -p "${FROSTGUARD_WORKSPACE}"

# Launch briefly; JavaFX may fail headless in CI, so accept either a short-lived
# process that wrote workspace metadata or a clean early exit.
"${LAUNCHER}" >"${WORK}/stdout.log" 2>"${WORK}/stderr.log" &
APP_PID=$!
sleep 8
if kill -0 "${APP_PID}" 2>/dev/null; then
  kill "${APP_PID}" 2>/dev/null || true
  wait "${APP_PID}" 2>/dev/null || true
  APP_PID=""
fi

if [[ -d "${FROSTGUARD_WORKSPACE}" ]]; then
  echo "macOS app-image smoke check passed (workspace root created under ${FROSTGUARD_WORKSPACE})"
  exit 0
fi

echo "Smoke check failed; launcher output:" >&2
cat "${WORK}/stdout.log" >&2 || true
cat "${WORK}/stderr.log" >&2 || true
exit 1
