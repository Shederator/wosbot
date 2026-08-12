# Contributing to Frostguard

Thank you for helping improve Frostguard. Contribution is broader than writing
code: reliable reports, testing, documentation, ideas, and community support are
all valuable.

## Ways to contribute

### Help users and share feedback

Join the [Frostguard Discord](https://discord.gg/sUthSHRVvU) to help with setup,
discuss how tasks behave, suggest improvements, participate in testing, and
share useful feedback with the community.

### Report a bug

Use the repository's [issue forms](https://github.com/Shederator/wosbot/issues/new/choose)
for reproducible bugs. Include:

- what you expected and what happened instead;
- the affected Frostguard version and release channel;
- emulator name and relevant display or game settings;
- minimal reproduction steps;
- sanitized logs or screenshots when they add useful evidence.

Remove credentials, account identifiers, private messages, emulator-specific
local paths, and unrelated personal data before sharing evidence.

### Suggest a feature

Start with Discord when an idea would benefit from community discussion. Use a
GitHub feature request when the desired outcome and scope are concrete enough
to track as project work. Check the
[public Frostguard project board](https://github.com/users/Shederator/projects/2)
before opening a duplicate request.

### Test Stable, Nightly, or pull requests

Testing on real emulator setups is especially useful for automation, OCR, and
image-recognition changes:

- verify the behavior requested by the issue or pull request;
- record the Frostguard build, emulator, and affected profile configuration;
- distinguish observed behavior from assumptions;
- provide sanitized screenshots or logs when possible;
- report both successful coverage and remaining failures.

Use the [installation guide](docs/installation.md) for release channels and PR
builds. Do not treat Nightly or unmerged PR builds as replacements for Stable.

### Improve documentation

Documentation changes can clarify installation, configuration, supported
behavior, diagnostics, or development workflows. Keep guidance concise and
accurate, preserve important limitations, and do not claim checks that were not
performed.

### Contribute code

Follow the [developer setup](docs/development.md) for checkout, build, focused
tests, full verification, and local startup. Shared engineering and verification
rules live in [AGENTS.md](AGENTS.md); follow the technical documents it routes
for the affected area.

## Submit a code or documentation change

1. Create a focused feature, fix, or documentation branch from `main`.
2. Follow `AGENTS.md` and the relevant technical guidance.
3. Add tests or saved-frame fixtures where they preserve the changed behavior.
4. Run at least the affected checks and record their actual results.
5. Open a pull request using the repository template as an adaptable review
   guide.

Make the complete behavior change, motivation, validation, and remaining risk
easy to find. Never imply that an unperformed check passed. UI, emulator, OCR,
and vision changes should include sanitized screenshots, logs, or saved-frame
evidence when available.

## Improve project visibility

Stars and accurate recommendations help other Whiteout Survival players find
the official Frostguard repository instead of unofficial mirrors. You can also
help by linking the official releases, welcoming new Discord users, and sharing
clear setup guidance.
