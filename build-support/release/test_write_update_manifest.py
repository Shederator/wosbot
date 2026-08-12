#!/usr/bin/env python3

from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from write_update_manifest import write_manifest  # noqa: E402


class WriteUpdateManifestTest(unittest.TestCase):
    def test_writes_hash_size_identity_and_channel_after_installer_exists(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            installer = root / "Frostguard-Nightly-3.1.0-nightly.20260811.1-windows-x64.msi"
            installer.write_bytes(b"signed-installer")
            output = root / "manifest.json"

            manifest = write_manifest(
                channel="nightly",
                version="3.1.0-nightly.20260811.1",
                minimum_updater_version="3.0.0-nightly.0",
                published_at="2026-08-11T14:00:00Z",
                release_notes_url="https://github.com/Shederator/wosbot/releases/tag/nightly-test",
                installer_url=f"https://github.com/Shederator/wosbot/releases/download/nightly-test/{installer.name}",
                installer=installer,
                publisher="CN=Frostguard Project, O=Frostguard",
                output=output,
            )

            self.assertEqual(16, manifest["artifacts"]["windows-x64"]["size"])
            self.assertEqual(64, len(manifest["artifacts"]["windows-x64"]["sha256"]))
            self.assertEqual("3.0.0-nightly.0", manifest["minimumUpdaterVersion"])
            self.assertEqual("CN=Frostguard Project, O=Frostguard",
                             manifest["artifacts"]["windows-x64"]["signature"]["publisher"])
            self.assertEqual(manifest, json.loads(output.read_text(encoding="utf-8")))

    def test_omits_optional_authenticode_requirement(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            installer = root / "Frostguard-3.0.1-windows-x64.msi"
            installer.write_bytes(b"unsigned but project-authenticated")

            manifest = write_manifest(
                channel="stable",
                version="3.0.1",
                minimum_updater_version="3.0.0",
                published_at="2026-08-12T00:00:00Z",
                release_notes_url="https://example.com/releases/3.0.1",
                installer_url=f"https://example.com/releases/3.0.1/{installer.name}",
                installer=installer,
                output=root / "manifest.json",
            )

            self.assertNotIn("signature", manifest["artifacts"]["windows-x64"])

    def test_rejects_mutable_or_cross_channel_inputs(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            installer = root / "Frostguard-latest.msi"
            installer.write_bytes(b"installer")
            common = dict(
                channel="stable",
                version="3.0.1-nightly.1",
                minimum_updater_version="3.0.0",
                published_at="2026-08-11T14:00:00Z",
                release_notes_url="https://example.com/release",
                installer_url="https://example.com/Frostguard-latest.msi",
                installer=installer,
                publisher="CN=Frostguard",
                output=root / "manifest.json",
            )

            with self.assertRaises(ValueError):
                write_manifest(**common)

    def test_rejects_exe_wrapper(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            installer = root / "Frostguard-3.0.1-windows-x64.exe"
            installer.write_bytes(b"wrapper")

            with self.assertRaisesRegex(ValueError, "MSI package"):
                write_manifest(
                    channel="stable",
                    version="3.0.1",
                    minimum_updater_version="3.0.0",
                    published_at="2026-08-12T00:00:00Z",
                    release_notes_url="https://example.com/releases/3.0.1",
                    installer_url=f"https://example.com/releases/3.0.1/{installer.name}",
                    installer=installer,
                    output=root / "manifest.json",
                )


if __name__ == "__main__":
    unittest.main(verbosity=2)
