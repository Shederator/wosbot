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

## Discord `#downloads`

Keep the channel read-only for regular users. It should contain two maintained
messages rather than a chronological build log.

### Stable message

```text
✅ Frostguard Stable — Recommended

The latest tested Windows version for regular use.
Download: <latest stable release URL>

Install Java 21+, extract the complete ZIP, then double-click
Start Frostguard.bat.
```

Replace this message only when a new stable release is published. The release
workflow sends the one-time server notification separately.

### Daily message

```text
🧪 Frostguard Daily — Latest Development Build

Includes the newest changes from main and may be less stable.
Download: https://github.com/Shederator/wosbot/releases/download/nightly/frostguard-windows-desktop-bundle.zip

No GitHub login is required. Install Java 21+, extract the complete ZIP, then
double-click Start Frostguard.bat.
```

The URL is deliberately version-independent. Do not post a new Discord message
for every daily build. Build failures belong in a maintainer channel, not in
`#downloads`.

## Migration

1. Create `#downloads` and post the Stable and Daily messages.
2. Publish the first real stable release before calling it recommended.
3. Point the nightly webhook away from the public downloads channel or change
   it to edit the maintained Daily message.
4. Move `/build-pr` results to `#request-a-build`.
5. Archive `#release` and `#download` after their links have been replaced.
