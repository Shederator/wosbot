# Close-cross detection

`CloseCrossDetector` is a reusable vision primitive for locating light X-shaped
close controls. Callers select a predefined screen region or pass an explicit
inclusive area. Results contain bounds, center, and score in full-frame
coordinates; the detector never taps or decides whether the current screen is
safe to dismiss.

The detector uses multi-scale grayscale OpenCV template matching. This covers
the observed light crosses across different background colors without tying the
vision module to a task or overlay lifecycle. The existing startup overlay
dismissal remains separate and retains its own narrow search, threshold, and
bounded tap attempts.

## Evidence and limits

Initialization now uses the shared detector in its measured
`(540, 65)`–`(680, 200)` area, after higher-priority startup blockers have been
checked. A full upper-right quarter produced a false match on Welcome back in
saved-frame tests, so the narrower custom-area overload is used. The existing three
dismissal limit and fresh home/world postcondition remain in place. The runtime
passes the raw emulator frame directly to avoid an intermediate image
conversion.

The saved-frame set contains four positive close-control crops spanning
top-right and middle-right positions and one nearby green plus negative. All
source captures contained identifying account or map details; test fixtures
retain only manually reviewed crops around the relevant controls. The detector
and startup integration have saved-frame tests, and annotated frames are in
`tools/close-cross-detection/evidence/`.

The initial 55% threshold is empirical for this small set. The green plus was
rejected in the saved frame, but this is not broad validation of other icon
shapes. The startup integration still needs an isolated live run and account-log
confirmation. Consumers remain responsible for their own screen-state and
action-safety decisions.
