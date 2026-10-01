# Storehouse detection overlay

Draws the colour bubble detector or the live template search on copies of
Storehouse screenshots. Accepted white bubbles are green, rejected regions
are red, and the cyan cross is the bubble centre. Template mode draws only
match centres. The task itself still taps with `matchTemplate`.

Run it from the repository root:

```sh
./tools/storehouse-detection/detect.sh \
  modules/tasks/src/test/resources/storehouse
```

A directory is walked for PNG files. `--output` selects the directory.
The default is `tools/storehouse-detection/target/detections`, which stays
out of git.

`--search color` is the default. `--search template` runs chest/chest2/chest3
at threshold 75, then the stamina can at 90, the same cuts as
`StorehouseChestRoutine`. `--do-benchmark` measures the selected search only.

```sh
./tools/storehouse-detection/detect.sh --search template \
  modules/tasks/src/test/resources/storehouse/day-crate-ready.png
```

Colour search reuses `ColorComponents` and `PixelStats`: flood-fill label-white
pixels in the on-building band, then classify the interior as crate wood or
stamina copper. A later OpenCV comparison that is still unused is
`matchTemplate` inside that white blob rather than on the full frame.
