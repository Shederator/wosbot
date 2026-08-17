#!/usr/bin/env bash
#
# Verify that every Git LFS asset in the checkout is a real binary.
#
# The Windows OpenCV .dll, adb.exe and the .traineddata OCR models are all
# tracked with Git LFS. If any of them is still a pointer stub the build
# silently produces a bundle that only fails on a user's machine, so fail
# loudly here instead. Shared by the nightly bundle workflow and the PR test
# build workflow so the two can never drift apart.
#
# Usage: build-support/verification/check_lfs_assets.sh [workspace]

set -euo pipefail

workspace="${1:-.}"
cd "${workspace}"

git lfs pull

mapfile -t assets < <(git lfs ls-files --name-only)

# An empty list would make every check below vacuously succeed, which is
# exactly the failure mode this script exists to prevent.
if [[ "${#assets[@]}" -eq 0 ]]; then
  echo "::error::git lfs ls-files returned no assets. LFS tracking or" \
       "the checkout is broken; the bundle would ship pointer stubs."
  exit 1
fi

# These are the assets the shipped bundle cannot work without.
required=(
  "modules/vision/src/main/resources/native/opencv/opencv_java4110.dll"
  "tools/adb/adb.exe"
  "tools/adb/AdbWinApi.dll"
  "tools/tesseract/eng.traineddata"
)
for want in "${required[@]}"; do
  found=0
  for asset in "${assets[@]}"; do
    [[ "${asset}" == "${want}" ]] && found=1 && break
  done
  if [[ "${found}" -ne 1 ]]; then
    echo "::error::Expected LFS-tracked asset is not tracked: ${want}"
    exit 1
  fi
done

failed=0
for asset in "${assets[@]}"; do
  [[ -z "${asset}" ]] && continue
  if [[ ! -f "${asset}" ]]; then
    echo "::error file=${asset}::LFS asset is missing from the checkout."
    failed=1
    continue
  fi
  # A pointer stub is a ~130 byte text file starting with this URL.
  if head -c 45 "${asset}" | grep -q 'git-lfs.github.com/spec'; then
    echo "::error file=${asset}::LFS asset was not materialised (still a pointer stub)."
    failed=1
    continue
  fi
  # Every real asset here is far larger than any stub could be.
  size="$(stat -c%s "${asset}")"
  if [[ "${size}" -lt 10240 ]]; then
    echo "::error file=${asset}::LFS asset is implausibly small (${size} bytes)."
    failed=1
  fi
done

if [[ "${failed}" -ne 0 ]]; then
  exit 1
fi
echo "All ${#assets[@]} Git LFS assets were materialised."
