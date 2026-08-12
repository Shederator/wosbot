# Releases

Frostguard publishes project-authenticated installed releases for Stable and
Nightly plus temporary ZIP builds for pull-request testing. Public channel links
are permanent even though the versioned installers behind them change.

| Type | Permanent entry | Versioned tag | Lifetime |
|---|---|---|---|
| Stable | [`releases/latest`](https://github.com/Shederator/wosbot/releases/latest) | `vX.Y.Z` | Permanent |
| Nightly | [`releases/tag/nightly`](https://github.com/Shederator/wosbot/releases/tag/nightly) | `vX.Y.Z-nightly.YYYYMMDD.N` | Current immutable prerelease plus rolling channel |
| PR test | Discord `/build-pr` | `pr-test-*` | Temporary |

Do not add a separate `stable` rolling release. GitHub's built-in `latest`
redirect already resolves the newest non-prerelease Stable without duplicating
it in the release list. The mutable `nightly` release is different: GitHub has
no built-in latest-prerelease URL, so it provides both the permanent landing
page and the stable signed-manifest URL required by installed Nightly clients.

## Authenticated installed releases

**Windows Channel Release** runs Nightly automatically once per day
from `main`. It reads the previous signed feed and derives the next version,
resetting the sequence to 1 on a new UTC date. A maintainer can also run it
manually for Stable or an additional Nightly. Stable versions use `X.Y.Z`;
Nightly versions use an immutable prerelease such as
`3.1.0-nightly.20260811.1`.

Windows Installer compares only three numeric version fields. Stable maps
directly to `X.Y.Z`. Nightly derives an independent, monotonically increasing
Windows identity from `YYYYMMDD.N`; for example, the Nightly above uses
`26.8.11001`. Use the current date and a sequence from 1 through 999, increasing
the sequence for additional Nightlies on the same day.

The workflow always requires
`FROSTGUARD_UPDATE_SIGNING_PRIVATE_KEY_BASE64`: the Base64-encoded PKCS#8
Ed25519 private key matching the public key committed in
`modules/update/src/main/resources/dev/frostguard/update/project-update-key.properties`.
Keep a second, access-controlled backup of the private key because GitHub
Actions secrets cannot be exported again.

Authenticode is optional. Configure all three of
`FROSTGUARD_WINDOWS_SIGNING_CERTIFICATE_BASE64`,
`FROSTGUARD_WINDOWS_SIGNING_CERTIFICATE_PASSWORD`, and
`FROSTGUARD_AUTHENTICODE_PUBLISHER`, or leave all three unset. A partial
configuration fails the release.

Stable and Nightly use different application IDs, upgrade UUIDs, install
directories, shortcuts, workspaces, and feeds. The workflow builds and smokes
the selected identity, optionally Authenticode-signs the installer, uploads and
re-downloads the immutable installer, derives its final size and SHA-256,
project-signs the manifest, verifies it, and publishes it last. Stable exposes
its manifest through the latest immutable release. Nightly stores the installer
in an immutable `v<version>` release and updates the manifest asset on the
rolling `nightly` release only after that installer is public and verified.

A failure before publication removes the abandoned draft release and tag so the
same immutable version can be retried. If a Nightly release becomes public but
promotion of the rolling `nightly` manifest fails afterward, leave the
immutable release intact and keep the previous rolling manifest active. Recover
by verifying and promoting the manifest asset from that immutable release; do
not rebuild or replace its installer.

Nightly builds embed only the permanent `nightly` endpoint. The temporary feed
used by pre-release development builds has been retired and must not be
recreated.

### Unpublished Stable release candidates

Before promoting a new Stable major or minor version, manually run **Windows
Installers** with a prerelease application version such as `3.0.0-rc.1` and a
numeric Windows Installer version below the final release, such as `2.99.1`.
The workflow validates that ordering, builds with release updates disabled,
verifies the pinned Stable launcher hashes, smoke-tests the image, and uploads
the MSI only as a short-lived Actions artifact. It does not create a GitHub
Release or change `releases/latest`.

Use the artifact to prove a real upgrade from the current Stable installation.
The candidate's numeric MSI version must be newer than the installed Stable but
lower than the final release, so the final `3.0.0` installer can still upgrade
it. The application displays the prerelease version from its JAR independently
of the numeric MSI version.

## Legacy 2.x ZIP promotion

The manual-only **Legacy Windows Bundle Candidate** workflow can still produce
an Actions artifact for an intentional 2.x maintenance release. It no longer
publishes a Nightly, has no schedule, and does not update Discord. The legacy
Stable ZIP workflow promotes one of those successful runs from `main`; it does
not rebuild a different tree. Run **Stable Windows Release** manually with:

- `version`: the `X.Y.Z` value declared in `pom.xml`;
- `daily_run_id`: a successful manually triggered legacy candidate run from
  `main` after the intended release commit.

This workflow rejects Frostguard 3.x. Installed 3.x releases must use the
authenticated channel workflow so an unsigned ZIP can never become the latest Stable
product accidentally.

The workflow pins the run's exact commit, downloads its versioned artifact,
re-runs structural and launch verification, creates the immutable `vX.Y.Z`
release, verifies its public download URL and then updates the maintained
Stable download without mentioning users. Existing stable tags are never
replaced.

## Discord `#download`

Keep the channel read-only for regular users. Pin the maintained Stable message
and keep exactly one Nightly message directly below it. Both cards are edited
in place; GitHub Releases remains the permanent release history.

### Pinned guide

```text
📥 Frostguard Downloads

Stable — versioned installer
A tested, self-contained Windows build that changes only with a Stable release:
https://github.com/Shederator/wosbot/releases/latest

Nightly — authenticated previews
The current immutable preview with its own app and settings:
https://github.com/Shederator/wosbot/releases/tag/nightly

Download the Windows x64 MSI from the selected release. The installer includes
Java. A Windows Unknown publisher or SmartScreen warning is currently expected.
```

The Stable guide links to GitHub's Latest release because the immutable MSI
filename contains its version. Store the webhook-owned card ID in
`DISCORD_STABLE_MESSAGE_ID`. Run `Refresh Stable Discord Message` after a Stable
promotion; it resolves and verifies the exact versioned MSI from Latest before
updating the maintained card.

### Maintained Nightly message

```text
Latest Nightly — Frostguard <version>

The newest automated development build. It may contain unfinished or unstable
changes.

Download Frostguard <version> for Windows

Run the self-contained per-user MSI installer. A separate Java installation is
not required.

Release notes · Latest Nightly
```

Do not post a new Discord message for every daily build. Store the webhook-owned
message ID in the repository variable `DISCORD_DAILY_MESSAGE_ID`; successful
native Nightly publications edit that message with the immutable MSI URL and
permanent channel URL. Build failures link to Actions and do not replace the
last working public download. Run **Refresh Nightly Discord Message** to repair
the card from the current project-signed feed without building another MSI.

## Migration

1. Create `#download` and post the Stable and Nightly messages.
2. Publish the first real Stable release before presenting the Stable download.
3. Store both maintained webhook message IDs as repository variables.
4. Move `/build-pr` results to `#request-a-build`.

## Native installer update contract

The Frostguard 3.0 updater uses one project-signed manifest envelope per
channel. Do not publish an envelope until its installer has been built,
uploaded to an immutable HTTPS URL, and smoke-tested. The public verification
key is part of the application; the private signing key remains outside the
repository.

Windows release artifacts are direct MSI packages. Do not switch the feed back
to jpackage's EXE bootstrap wrapper: installed Frostguard invokes Windows
Installer directly, and existing schema-1 clients can use an MSI artifact
without a manifest-format migration. Authenticode signing remains additive and
can be applied to the final MSI later.

### Signed envelope 1

```json
{
  "envelopeVersion": 1,
  "algorithm": "Ed25519",
  "keyId": "frostguard-update-2026-01",
  "payload": "<Base64 of the exact UTF-8 schema-1 manifest bytes>",
  "signature": "<Base64 Ed25519 signature over those exact bytes>"
}
```

The updater rejects an unsigned raw manifest, an unknown envelope field,
algorithm, or key ID, invalid Base64, and any payload whose signature does not
verify against the embedded project key. It parses and selects an artifact only
after signature verification.

### Manifest schema 1

```json
{
  "schemaVersion": 1,
  "channel": "stable",
  "version": "3.0.1",
  "publishedAt": "2026-08-10T04:00:00Z",
  "minimumUpdaterVersion": "3.0.0",
  "releaseNotesUrl": "https://example.invalid/releases/3.0.1",
  "artifacts": {
    "windows-x64": {
      "operatingSystem": "windows",
      "architecture": "x64",
      "fileName": "Frostguard-3.0.1-windows-x64.msi",
      "url": "https://example.invalid/releases/3.0.1/Frostguard-3.0.1-windows-x64.msi",
      "sha256": "<64 lowercase hexadecimal characters>",
      "size": 123456789
    }
  }
}
```

Unknown fields, unsupported schemas, mutable filenames, and insecure URLs are
rejected. If Authenticode is configured, the artifact additionally carries a
`signature` object with type `authenticode` and the exact certificate subject.
Calculate the hash and size after optional Authenticode signing because signing
changes the file.

### Build inputs

Embed the Stable endpoint at packaging time. The project verification key is a
versioned source resource and is always included in release builds:

```powershell
.\mvnw.cmd -Dfrostguard.update.manifest.stable=https://updates.example.invalid/stable.json `
  "-Pwindows-app-image,windows-installer" package
```

Nightly adds its separate packaging identity and embeds both public endpoints:

```powershell
.\mvnw.cmd -Dfrostguard.update.manifest.stable=https://example.invalid/stable.json `
  -Dfrostguard.update.manifest.nightly=https://example.invalid/nightly.json `
  "-Pwindows-app-image,windows-installer,windows-nightly" package
```

The checked-in endpoint defaults are empty, so ordinary local builds cannot
contact a release feed accidentally. PR packaging also embeds
`frostguard.update.pullRequestBuild=true`. Development and PR builds cannot
update even if someone supplies a manifest URL manually. Release builds trust
only envelopes signed by the project key embedded in their update module. If a
build also pins an Authenticode publisher, the manifest and downloaded
installer must match it exactly.

### Publication order

1. Build and smoke-test the native application image.
2. Build the channel-specific installer with its stable upgrade identity.
3. Optionally Authenticode-sign the final installer and verify its exact subject.
4. Calculate the final byte size and SHA-256.
5. Upload and re-download the installer at its immutable versioned HTTPS URL.
6. Generate the schema-1 payload from that verified file.
7. Ed25519-sign the exact payload and verify the resulting envelope.
8. Publish the signed envelope atomically as the final step.

Never publish a PR artifact, unsigned manifest payload, mutable installer
filename, or envelope whose artifact has not completed the same verification
sequence.

### Key lifecycle

Generate a replacement pair with
`ProjectManifestSigner generate <private-output> <public-output>`. Restrict the
private file to release maintainers, keep an offline backup, place its Base64
value in the repository secret, and commit only the Base64 X.509 public key with
a new key ID. Never commit, log, upload as an artifact, or place the private key
in a workflow variable that is printed.

Rotation is staged. First publish an old-key-signed bridge release whose
application embeds the new public key. Keep the old private key and old bridge
release available while supported installations move through it. Only then
replace the repository secret and publish envelopes with the new key ID. With
the current single-key client, installations that skip the bridge cannot trust
the new feed and must install a current release manually. Supporting overlapping
keys is the follow-up if seamless emergency rotation is required.

If the private key is lost, restore it from the offline backup; the GitHub
secret cannot be read back. If compromise is suspected, stop channel
publication, remove or replace the Actions secret, preserve release evidence,
and prepare a bridge release and manual recovery instructions before resuming.
Adding a Windows code-signing certificate later is additive: configure the
three Authenticode secrets above; the signed-envelope format does not change.

### Runtime and recovery

Downloads belong to the selected workspace under
`cache/updates/<channel>/<version>`. Incomplete data uses a `.part` suffix and
is never exposed as a completed installer. Completion requires an atomic rename
after size and hash verification.

The external Windows handoff receives the Frostguard PID plus a one-time token.
Frostguard authorizes the staged waiter immediately before coordinated
shutdown. The waiter cannot start the installer while the Frostguard PID is
alive, and a failed shutdown deletes the token so a later unrelated application
exit cannot launch the staged installer.
