# Releases

Frostguard publishes three distinct Windows bundle types. Do not mix their
notifications or download locations.

| Type | Audience | Lifetime | Discord notification |
|---|---|---|---|
| Stable `vX.Y.Z` | Regular users | Permanent | Update the maintained Stable message |
| Daily `nightly` | Testers | Replaced daily | Update the daily download, no mass mention |
| PR test `pr-test-*` | Requester/testers | Temporary | Reply only to the requester |

## Stable promotion

Stable releases promote an already successful `Daily Windows Bundle` run from
`main`; they do not rebuild a different tree. Run **Stable Windows Release**
manually with:

- `version`: the `X.Y.Z` value declared in `pom.xml`;
- `daily_run_id`: a successful scheduled or manually triggered daily run from
  `main` after the intended release commit.

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

Stable — versioned
A tested build that changes only when a new Stable is published:
https://github.com/Shederator/wosbot/releases/latest/download/frostguard-windows-desktop-bundle.zip

Nightly — testing
The newest automated development build. It may contain unfinished changes:
https://github.com/Shederator/wosbot/releases/download/nightly/frostguard-windows-desktop-bundle.zip

Extract the complete archive and use the included Frostguard launcher.
Java 21 or newer is required.
```

The Stable URL is deliberately a direct, version-independent asset URL. GitHub
redirects it to the asset on the latest non-prerelease release. Store the
webhook-owned card ID in `DISCORD_STABLE_MESSAGE_ID`. A Stable promotion updates
the card automatically; `Refresh Stable Discord Message` repairs it manually
from GitHub's Latest release when necessary.

### Nightly message

```text
Latest Nightly — Frostguard <version>

The newest automated development build. It may contain unfinished or unstable
changes.

Download Frostguard <version> for Windows

Changes since the previous Nightly
- <linked PR title or direct commit subject>

Extract the complete archive and use the included Frostguard launcher.
Java 21 or newer is required.
```

The URL is deliberately version-independent. Do not post a new Discord message
for every daily build. Store the webhook-owned message ID in the repository
variable `DISCORD_DAILY_MESSAGE_ID`; successful builds edit that message. Show
at most five linked first-parent changes since the previous Nightly and collapse
older entries into a count.
Build failures remain visible in Actions and do not replace the last working
public download.

## Migration

1. Create `#download` and post the Stable and Nightly messages.
2. Publish the first real Stable release before presenting the Stable download.
3. Store both maintained webhook message IDs as repository variables.
4. Move `/build-pr` results to `#request-a-build`.
5. Archive redundant legacy release channels after their links are replaced.
