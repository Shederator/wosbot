# Storehouse chest-reward detection

Compares how Frostguard finds the Storehouse crate and stamina can. The live
task taps with `StorehouseBubbleDetector` (white bubble, then wood vs copper).
`--search template` and `--search chest3` stay for OpenCV comparison.

## Run

From the repository root:

```sh
./tools/storehouse-detection/detect.sh \
  modules/tasks/src/test/resources/storehouse
```

`--search` is `color` (default), `template` (live crops), or `chest3` (the
chosen OpenCV crop). `--compare` prints preprocess scores and writes no PNG.
`--output` defaults to `tools/storehouse-detection/target/detections` (gitignored).

```sh
./tools/storehouse-detection/detect.sh --compare \
  modules/tasks/src/test/resources/storehouse
./tools/storehouse-detection/detect.sh --search chest3 \
  modules/tasks/src/test/resources/storehouse/day-crate-ready.png
```

## Considerations

The claimable crate is a wooden icon in a **white speech bubble** on the
building. The stamina can uses the same bubble shape. The bubble bobs for
about a second. City lighting shifts blue at night.

`matchTemplate` (`TM_CCOEFF_NORMED`) follows a fixed crop. Translation is
fine; a change of appearance is not. The first templates (`chest.png`,
`chest2.png`) are **wood slats** with almost no bubble. Night scores ~80,
day ~71, so a 75 cut misses the day crate (T162209). Adding more wood PNGs
repeats that.

The bubble fill stays label-white (RGB > 225) day and night. Snow around
the building is colder (~192, 196, 221) and does not pass `GameColors.isLabelWhite`.

Tried:

1. Extra wood crops — `chest` / `chest2`; day still ~71.
2. White in the crop — `chest3.png`, the daylight bubble + crate.
3. Preprocess the same crop: greyscale, drop the blue channel, mean of R+G,
   CLAHE on grey. Same `matchTemplate` after applying the transform to both
   the frame and the template.
4. Colour components — flood-fill the white bubble, then count crate wood vs
   stamina copper inside (`StorehouseBubbleDetector`).

A template inside the bubble ROI was not measured: the icon still changes
pose, so a longer poll would help templates more than a smaller search box.

## Results

`--compare` on the five fixtures (2026-10-01). Scores are percent, best
location on the full 720×1280 frame.

| Frame | Mode | chest | chest2 | chest3 | stamina |
|---|---|---|---|---|---|
| day-crate-ready | BGR | 68.46 | 70.96 | **100.00** | 74.71 |
| day-crate-ready | GRAY | 68.25 | 67.16 | 100.00 | 78.76 |
| day-crate-ready | DROP_B | 66.45 | 66.91 | 100.00 | 77.35 |
| day-crate-ready | RG_MEAN | 66.53 | 65.22 | 100.00 | 78.80 |
| day-crate-ready | CLAHE | 44.97 | 39.03 | 96.01 | 77.64 |
| night-crate-ready | BGR | 77.16 | 79.85 | **91.96** | 79.38 |
| night-crate-ready | GRAY | 76.58 | 74.50 | 91.85 | 83.27 |
| city-can-visible | BGR | 49.95 | 42.85 | **61.19** | 94.60 |
| construction-stamina-3d | BGR | 48.28 | 43.34 | 57.94 | 93.83 |
| cooldown-white-pill | BGR | 49.95 | 44.22 | 44.74 | 77.75 |

Grey / drop-blue / RG-mean do not lift the wood crops above 75 on the day
crate. They raise the shop-icon stamina false match (74.7 → ~78) while
stamina on a real can stays ~94. CLAHE hurts every chest crop.

Colour search on the same fixtures: one accepted **chest** on day and night
crates, one accepted **stamina** on both can frames, nothing on the cooldown
pill.

## Choice

**OpenCV: keep `chest3` BGR at threshold 75.** Putting the white bubble in
the crop is the change that actually separates day crate (100) from a can
(61). Dropping blue or converting to grey does not beat that. `chest` and
`chest2` are then redundant for these frames.

**Live: colour search.** The visit must tap a crate or a stamina can. Colour
names the bubble from the white frame, not a pose crop, so it is the method
the routine uses. Template modes stay in this tool for comparison.

Re-run `--compare` after new lighting or animation captures.
