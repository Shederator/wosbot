# Releases

Frostguard publishes three distinct Windows bundle types. Do not mix their
notifications or download locations.

| Type | Audience | Lifetime | Discord notification |
|---|---|---|---|
| Stable `vX.Y.Z` | Regular users | Permanent | Notify everyone once |
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
release, verifies its public download URL and then sends the one permitted
`@everyone` release announcement. Existing stable tags are never replaced.

## Discord `#download`

Keep the channel read-only for regular users. Pin one short guide, keep
permanent Stable announcements as release history, and maintain exactly one
Nightly message that is edited in place.

### Pinned guide

```text
📥 Frostguard Downloads

Stable — recommended
The tested version for normal use:
https://github.com/Shederator/wosbot/releases/latest/download/frostguard-windows-desktop-bundle.zip

Nightly — testing
The newest automated development build. It may contain unfinished changes:
https://github.com/Shederator/wosbot/releases/download/nightly/frostguard-windows-desktop-bundle.zip

Extract the complete archive and use the included Frostguard launcher.
Java 21 or newer is required.
```

The Stable URL is deliberately a direct, version-independent asset URL. GitHub
redirects it to the asset on the latest non-prerelease release.

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

1. Create `#downloads` and post the Stable and Daily messages.
2. Publish the first real stable release before calling it recommended.
3. Point the nightly webhook away from the public downloads channel or change
   it to edit the maintained Daily message.
4. Move `/build-pr` results to `#request-a-build`.
5. Archive `#release` and `#download` after their links have been replaced.
