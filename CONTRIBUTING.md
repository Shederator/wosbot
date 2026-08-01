# Contributing to Frostguard

Contributions are welcome. This document covers the contribution workflow.
Shared engineering, build, test, and verification rules live in `AGENTS.md`;
the technical documents linked there remain the canonical design references.

## Set Up The Project

Follow `docs/installation.md` for Java, Maven, Git LFS, emulator, and source
setup. Windows-specific runtime guidance is in `docs/windows.md`.

## Submit A Change

1. Create a focused feature or fix branch from `main`.
2. Follow `AGENTS.md` and the technical document it routes the change to.
3. Add focused tests or saved-frame fixtures where they preserve the behavior.
4. Run the relevant checks from `AGENTS.md`.
5. Open a pull request using the repository template as an adaptable review
   guide.

Make the behavior change, motivation, checks actually performed, and remaining
risk easy to find. Include sanitized screenshots, logs, or saved-frame evidence
for UI, emulator, OCR, and vision changes. Never imply an unperformed check
passed.

## Report Bugs And Request Features

Use the repository issue forms for reproducible bugs and feature proposals.
Remove credentials, account identifiers, private messages, and unrelated log
content before attaching evidence. Use the project Discord for general support
and usage questions.
