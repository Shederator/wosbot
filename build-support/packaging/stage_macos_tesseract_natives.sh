#!/usr/bin/env bash
# Stage Homebrew Tesseract/Leptonica dylibs into a jpackage input directory so
# Tess4J can load natives from the app bundle without requiring brew on end-user Macs.
set -euo pipefail

DEST="${1:-}"
if [[ -z "${DEST}" ]]; then
  echo "Usage: $0 <dest-dir>" >&2
  exit 2
fi

mkdir -p "${DEST}"

resolve_lib() {
  local name="$1"
  local candidate
  for candidate in \
      "/opt/homebrew/opt/${name}/lib" \
      "/usr/local/opt/${name}/lib" \
      "/opt/homebrew/lib" \
      "/usr/local/lib"; do
    if [[ -d "${candidate}" ]]; then
      echo "${candidate}"
      return 0
    fi
  done
  return 1
}

TESS_LIB="$(resolve_lib tesseract || true)"
LEPT_LIB="$(resolve_lib leptonica || true)"
if [[ -z "${TESS_LIB}" || ! -e "${TESS_LIB}/libtesseract.dylib" && ! -e "${TESS_LIB}/libtesseract.5.dylib" ]]; then
  echo "libtesseract not found. Install with: brew install tesseract leptonica" >&2
  exit 1
fi

ROOT_LIB=""
if [[ -e "${TESS_LIB}/libtesseract.dylib" ]]; then
  ROOT_LIB="${TESS_LIB}/libtesseract.dylib"
else
  ROOT_LIB="${TESS_LIB}/libtesseract.5.dylib"
fi

python3 - "${ROOT_LIB}" "${DEST}" <<'PY'
import os
import shutil
import subprocess
import sys
from pathlib import Path

root = Path(sys.argv[1]).resolve()
dest = Path(sys.argv[2]).resolve()
dest.mkdir(parents=True, exist_ok=True)

seen: set[Path] = set()
queue: list[Path] = [root]

def deps(path: Path) -> list[Path]:
    try:
        out = subprocess.check_output(["otool", "-L", str(path)], text=True, stderr=subprocess.DEVNULL)
    except Exception:
        return []
    found: list[Path] = []
    for line in out.splitlines()[1:]:
        dep = line.strip().split(" (", 1)[0].strip()
        if dep.startswith("/opt/homebrew/") or dep.startswith("/usr/local/"):
            found.append(Path(dep))
    return found

while queue:
    current = queue.pop()
    if current in seen or not current.is_file():
        continue
    seen.add(current)
    for dep in deps(current):
        if dep not in seen:
            queue.append(dep)

copied: list[Path] = []
for src in sorted(seen):
    target = dest / src.name
    shutil.copy2(src, target)
    os.chmod(target, 0o755)
    copied.append(target)
    # Also expose unversioned aliases Tess4J/JNA commonly resolve.
    if src.name.startswith("libtesseract.") and not (dest / "libtesseract.dylib").exists():
        (dest / "libtesseract.dylib").symlink_to(src.name)
    if src.name.startswith("libleptonica.") and not (dest / "libleptonica.dylib").exists():
        (dest / "libleptonica.dylib").symlink_to(src.name)
    if src.name.startswith("liblept.") and not (dest / "liblept.dylib").exists():
        (dest / "liblept.dylib").symlink_to(src.name)

# Rewrite install names to @loader_path so the bundle works without Homebrew.
names = {path.name for path in copied}
for binary in copied:
    try:
        out = subprocess.check_output(["otool", "-L", str(binary)], text=True, stderr=subprocess.DEVNULL)
    except Exception as exc:
        print(f"warning: otool failed for {binary}: {exc}", file=sys.stderr)
        continue
    for line in out.splitlines()[1:]:
        old = line.strip().split(" (", 1)[0].strip()
        base = Path(old).name
        if base in names and old != f"@loader_path/{base}":
            try:
                subprocess.check_call(
                    ["install_name_tool", "-change", old, f"@loader_path/{base}", str(binary)],
                    stdout=subprocess.DEVNULL,
                    stderr=subprocess.DEVNULL,
                )
            except Exception as exc:
                print(f"warning: install_name_tool failed for {binary}: {exc}", file=sys.stderr)
    try:
        subprocess.check_call(
            ["install_name_tool", "-id", f"@loader_path/{binary.name}", str(binary)],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
    except Exception:
        pass

print(f"Staged {len(copied)} tesseract native libraries into {dest}")
PY
