# Frostguard feature overview

Frostguard combines configurable game routines with multi-profile scheduling,
visual navigation, and runtime diagnostics. Availability and exact behavior can
change as Whiteout Survival evolves; use Stable for normal operation and check
the release notes for recent changes.

## Combat and events

- Arena battles, including configurable target and formation behavior
- Polar Terror hunts
- Bear Trap preparation, protection, participation, and rally behavior
- Alliance rally autojoin
- Beast Hunting with march, level, and stamina settings
- Tundra Truck processing
- Alliance Championship configuration
- Alliance Mobilization tasks

## City progression

- Troop training and promotion
- Research automation
- Furnace and city upgrade priorities
- Intel missions and Expert management
- Resource gathering
- Hero recruitment
- Crystal Lab and War Academy collection
- Daily missions, Mail, VIP, and recurring city rewards

## Pets and exploration

- Pet Adventure food, treasure, and stamina handling
- Tundra Trek
- Journey of Light
- Exploration battles and chest collection
- Wilderness exploration routines

## Alliance, shops, and rewards

- Alliance Tech contribution
- Alliance Gifts and help requests
- Alliance Shop purchasing priorities
- Nomadic Merchant processing
- Gift Code Hub
- Time-based and recurring reward collection

## Profiles and scheduling

- Multiple profiles with independent configuration and priorities
- Search, filtering, tags, saved views, import, export, and bulk updates
- Independent Stable, Nightly, and development workspaces
- Priority-aware scheduling for time-sensitive events
- Queue pausing, resuming, and controlled task injection
- Configurable idle, stop, reconnect, and autostart behavior

## Visibility and diagnostics

- Live runtime logs filterable by profile, task, and level
- Profile and queue state in the desktop interface
- ADB capture and image-recognition debugging tools
- Workspace-specific logs, caches, screenshots, and process locks
- Optional Telegram integration and watcher behavior

## How automation interacts with the game

Frostguard controls supported Android emulators through their command-line
interfaces and ADB. It observes the visible game UI using OCR and image
recognition and performs configured screen interactions through the emulator.
It does not modify the game client.

For required emulator and game settings, see the
[installation guide](installation.md). For implementation boundaries and
technical ownership, see [architecture.md](architecture.md).
