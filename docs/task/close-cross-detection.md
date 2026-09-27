# Close-cross detection

`CloseCrossDetector` is a reusable vision primitive for locating light X-shaped
close controls. It returns match bounds, center, and score; it never taps or
decides whether the current screen is safe to dismiss. Callers may provide an
inclusive search area. The default searches the right half of the frame, where
the observed controls appeared.

The detector uses multi-scale grayscale OpenCV template matching. This covers
the observed light crosses across different background colors without tying the
vision module to a task or overlay lifecycle. The existing startup overlay
dismissal remains separate and retains its own narrow search, threshold, and
bounded tap attempts.

## Evidence and limits

Initialization now uses the shared detector in its measured top-right area,
after higher-priority startup blockers have been checked. The existing three
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
