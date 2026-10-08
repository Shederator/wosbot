# Close-cross detection

`CloseCrossDetector` is a reusable vision primitive for locating light X-shaped
close controls. Callers select a predefined screen region or pass an explicit
inclusive area. Results contain bounds, center, and score in full-frame
coordinates; the detector never taps or decides whether the current screen is
safe to dismiss.

The detector uses multi-scale grayscale OpenCV template matching with a mask
derived from the template's bright close glyph. The mask excludes changing
offer artwork and header colors without tying the vision module to a task or
overlay lifecycle. Invalid normalized-correlation cells from uniform regions
are ignored. The existing startup overlay dismissal remains separate and
retains its own narrow search, threshold, and bounded tap attempts.

## Evidence and limits

Initialization now uses the shared detector in its measured
`(540, 65)`–`(680, 240)` area, after higher-priority startup blockers have been
checked. The lower edge was extended after a live offer's close-cross match
spanned y=145–205 and was clipped by the previous y=200 boundary. A full
upper-right quarter produced a false match on Welcome back in saved-frame tests,
so the narrower custom-area overload is used. The existing three dismissal
limit and fresh home/world postcondition remain in place. The runtime passes the
raw emulator frame directly to avoid an intermediate image conversion.

The saved-frame set contains six positive close-control crops spanning
top-right and middle-right positions and one nearby green plus negative. Earlier
source captures contained identifying account or map details; fixtures retain
only manually reviewed crops around the relevant controls. The detector and
startup integration have saved-frame tests, and annotated frames are in
`tools/close-cross-detection/evidence/`.

The initial 78% threshold is empirical for this small set. The masked live
Charm Master Pack regression frame scores 88.1% at its detected close control;
the green plus remains rejected. This is not broad validation of other icon
shapes. The startup integration still needs an isolated live run and account-log
confirmation. Consumers remain responsible for their own screen-state and
action-safety decisions.
