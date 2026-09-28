# Crystal Laboratory

Weekly RFC refinement runs on Monday when its target is positive, independently
of the daily discounted RFC purchase setting. The current FC and refined-FC
read regions are fixed to the established 720x1280 game layout; no saved
refined-FC panel fixture is available yet, so OCR accuracy on other layouts is
not established.

Null, blank, or unparseable readings are treated as failed OCR. After exhausting
the bounded retries, the routine saves a local full-screen diagnostic frame to
`logs/snapshot/` and logs its workspace-relative path when possible. Unexpected
screen or required-control misses also retain a frame. Expected missing crystal
claims or discounted offers do not. Frames are not redacted automatically;
review and redact them before sharing.

Weekly OCR, action, and insufficient-FC retries are capped at the next daily
reset. A detected discounted RFC offer whose purchase is not confirmed retries
in five minutes, also capped at that reset. The daily reset is used when the
offer is absent. Refinement taps are followed by an OCR check of the target
level before the routine reports success. Live account-log confirmation and
saved-frame OCR verification remain outstanding.
