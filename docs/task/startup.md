# Startup blockers

Initialization distinguishes startup blockers before attempting recovery:

- The resource-pack dialog has separate orange `Enter Game` and blue
  `Download Now` actions. Frostguard selects `Download Now` and waits up to ten
  minutes for a home/world postcondition so tasks do not run with missing
  assets.
- The English in-game `Welcome back` modal that can follow launch or resource
  download is dismissed at most once, and only when its title and concrete
  `Confirm` action both match in one fresh frame.
- Closeable promotional overlays are dismissed at most three times per
  initialization, and only when their concrete top-right close control matches
  in the measured startup region. Offer text, artwork, price, and currency are
  not used as evidence. Frostguard taps the matched control rather than sending
  a generic Back action.
- The mandatory app-update dialog is identified pattern-first from both its
  stable `Update` title and the concrete `Update` action, with the one-action
  pale-blue panel used as supporting layout evidence. Frostguard taps only the
  matched button area, then evaluates fresh frames for up to ten minutes.
- A home/world postcondition or the separate resource-download prompt continues
  automatically without an incident. An unknown follow-up receives no input;
  it yields the profile for fifteen minutes and follows the persistent generic
  failure budget rather than claiming that store authentication is required.
- A post-click Google Play redirect is operator-owned in this scope. Frostguard
  requires foreground package `com.android.vending` after capturing a fresh
  post-click frame, independently of Store language or subpage. It creates one deduplicated
  `ACTION REQUIRED` incident, sends no Store input, stops the blocked game
  process, releases the emulator slot, and retries after one hour. Merely seeing
  the original in-game update dialog is not enough to create an incident.
- An unknown blocker receives one bounded emulator restart. If home/world is
  still unavailable, Frostguard waits only for passive state change. It does
  not send speculative navigation input; the same profile-wide cooldown
  mechanism releases the slot for fifteen minutes instead of entering a
  restart loop.

The close-control template `closeableOverlayClose.png` is cropped from the
redacted real frame
`modules/tasks/src/test/resources/startup/closeable-offer-overlay-20260821.png`.
The detector runs only after reconnect, resource-download, Welcome-back, and
mandatory-update classification, preserving those higher-priority flows.

The measured mandatory-update title and button templates are
`mandatoryUpdateTitle.png` and `mandatoryUpdateButton.png`, cropped from the
real regression frame
`modules/tasks/src/test/resources/startup/mandatory-update-dialog-20260820.png`.
The current fix deliberately contains no Store-language OCR, Store button
template, localized Store full-frame fixture, or Store-subpage geometry. The
sign-in, app-detail, and Play Pass variants are retained only as external issue
evidence for future Store automation. The existing resource-download fixture
and flow remain separate.

`ProfileCooldownException` is reusable by other tasks after their own bounded
recovery is exhausted. A supplied action-required context escalates
immediately; a context-free cooldown contributes to the generic persistent
failure budget. The queue keeps the requested next run, force-stops only the
game process, releases the emulator slot, and reacquires it before resuming. If
slot release fails, it retains lease ownership instead of acquiring a second
slot.
