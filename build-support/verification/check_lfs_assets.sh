#!/usr/bin/env bash
#
# Verify that the native binaries shipped in the Windows bundle are real files.
#
# These used to be Git LFS objects. A pointer stub is a short text file, and a
# bundle that ships one fails only on a user's machine. An empty `git lfs
# ls-files` list is expected and is not a failure. Shared by the nightly bundle
# workflow and the PR test build workflow so the two can never drift apart.
#
# Usage: build-support/verification/check_lfs_assets.sh [workspace]

set -euo pipefail

workspace="${1:-.}"
cd "${workspace}"

# These are the binaries the shipped bundle cannot work without.
required=(
  "modules/vision/src/main/resources/native/opencv/opencv_java4110.dll"
  "tools/adb/adb.exe"
  "tools/adb/AdbWinApi.dll"
  "tools/adb/AdbWinUsbApi.dll"
  "tools/tesseract/chi_sim.traineddata"
  "tools/tesseract/eng.traineddata"
  "tools/tesseract/osd.traineddata"
)

failed=0
for asset in "${required[@]}"; do
  if [[ ! -f "${asset}" ]]; then
    echo "::error file=${asset}::Required binary is missing from the checkout."
    failed=1
    continue
  fi
  # A pointer stub is a ~130 byte text file. Read a fixed prefix without
  # piping into grep: pipefail plus grep -q can drop a real match on SIGPIPE.
  prefix="$(head -c 80 "${asset}" | tr -d '\0')"
  if [[ "${prefix}" == *git-lfs.github.com/spec* ]]; then
    echo "::error file=${asset}::Required binary is still a Git LFS pointer stub."
    failed=1
    continue
  fi
  # Every real asset here is far larger than any stub could be.
  size="$(stat -c%s "${asset}")"
  if [[ "${size}" -lt 10240 ]]; then
    echo "::error file=${asset}::Required binary is implausibly small (${size} bytes)."
    failed=1
  fi
done

if [[ "${failed}" -ne 0 ]]; then
  exit 1
fi
echo "All ${#required[@]} shipped binaries are real files."
