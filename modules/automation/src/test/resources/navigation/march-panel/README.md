# March panel evidence

`four-gathering-expanded.png` is a real 720×1280 World frame. Four manually sent
gathering rows and Add March Queue cover the fixed sidebar handle. The dropdown
caret and the non-identifying City button are preserved for real matching.

The standalone `tools/privacy-redactor/CityLabelRedactor.java` was applied first.
All map, profile, coordinate and chat information outside the evidence panel and
bottom controls was then replaced with opaque black. The redactor's broad chat
mask clipped the City button; only that non-identifying button was restored from
the source so the World anchor remains valid. The final image was manually
reviewed. No identifying source frame is part of these resources.

The flow tests derive synthetic collapsed and five-row states in memory. These
are explicitly simulated states, not additional live-account captures. The
production caret template contains no march count, duration or account text.
