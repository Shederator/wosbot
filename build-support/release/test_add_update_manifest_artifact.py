#!/usr/bin/env python3

from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from add_update_manifest_artifact import add_artifact  # noqa: E402


class AddUpdateManifestArtifactTest(unittest.TestCase):
    def test_adds_macos_pkg_beside_windows_msi(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            payload = root / "payload.json"
            payload.write_text(
                json.dumps({
                    "schemaVersion": 1,
                    "channel": "nightly",
                    "version": "3.1.0-nightly.20260811.1",
                    "publishedAt": "2026-08-11T14:00:00Z",
                    "minimumUpdaterVersion": "3.0.0-nightly.0",
                    "releaseNotesUrl": "https://example.com/release",
                    "artifacts": {
                        "windows-x64": {
                            "operatingSystem": "windows",
                            "architecture": "x64",
                            "fileName": "Frostguard-Nightly-3.1.0-nightly.20260811.1-windows-x64.msi",
                            "url": "https://example.com/Frostguard-Nightly-3.1.0-nightly.20260811.1-windows-x64.msi",
                            "sha256": "a" * 64,
                            "size": 12,
                        }
                    },
                }) + "\n",
                encoding="utf-8",
            )
            installer = root / "Frostguard-Nightly-3.1.0-nightly.20260811.1-macos-arm64.pkg"
            installer.write_bytes(b"mac-installer")
            output = root / "merged.json"

            manifest = add_artifact(
                payload=payload,
                platform="macos-arm64",
                installer=installer,
                installer_url=f"https://example.com/{installer.name}",
                output=output,
            )

            self.assertIn("windows-x64", manifest["artifacts"])
            self.assertEqual(13, manifest["artifacts"]["macos-arm64"]["size"])
            self.assertEqual("macos", manifest["artifacts"]["macos-arm64"]["operatingSystem"])
            self.assertEqual("arm64", manifest["artifacts"]["macos-arm64"]["architecture"])
            self.assertEqual(manifest, json.loads(output.read_text(encoding="utf-8")))

    def test_rejects_wrong_suffix(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            payload = root / "payload.json"
            payload.write_text(
                json.dumps({
                    "schemaVersion": 1,
                    "channel": "stable",
                    "version": "3.0.2",
                    "publishedAt": "2026-08-12T00:00:00Z",
                    "minimumUpdaterVersion": "3.0.0",
                    "releaseNotesUrl": "https://example.com/release",
                    "artifacts": {},
                }),
                encoding="utf-8",
            )
            installer = root / "Frostguard-3.0.2-macos-arm64.msi"
            installer.write_bytes(b"nope")
            with self.assertRaises(ValueError):
                add_artifact(
                    payload=payload,
                    platform="macos-arm64",
                    installer=installer,
                    installer_url=f"https://example.com/{installer.name}",
                    output=root / "out.json",
                )


if __name__ == "__main__":
    unittest.main()
