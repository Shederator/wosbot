# Settings input and validation

Frostguard settings use the following interaction rules:

- Filters may prevent characters that can never form a valid value, but users
  must still be able to leave an incomplete draft while editing.
- Recoverable field errors stay visible in the field and use the shared inline
  error state, message, tooltip, and accessible help text. Invalid drafts are
  never persisted.
- Text fields commit on Enter or focus loss. Controls with an explicit Apply
  button remain drafts until Apply is selected.
- Modal dialogs are reserved for destructive actions, cross-field failures, or
  decisions that require acknowledgement.
- Malformed persisted data may fall back to a declared default during loading.
  The fallback must be logged; a user's invalid draft must never be replaced by
  a default or an older value.
- Dependent controls are disabled when their setting is irrelevant. Nearby
  labels or tooltips should explain non-obvious dependencies.

Parsers, ranges, units, defaults, empty-value semantics, and commit policies
belong in shared setting definitions or validators rather than controller-local
listeners.

## Migration inventory

The shared approach currently covers Instance Settings, generic profile integer
and date/time fields, Bear Trap UTC editors, Arena and Tundra `HH:mm` fields,
Custom Tasks, and profile creation/editing.

Follow-up migrations should inventory and convert the remaining controller-local
parsing and styling in scheduler dialogs, Telegram settings, statistics filters,
and Task Builder editors. Those surfaces are intentionally outside issue #141's
representative migration set and must not silently acquire new validation
patterns in the meantime.
