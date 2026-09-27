# Close-cross detection

This tool runs the reusable `CloseCrossDetector` against saved PNG frames. It
draws a green box around each accepted match and a cyan cross at the returned
center. The tool does not tap or alter the input image.

Run it from the repository root:

```sh
./tools/close-cross-detection/detect.sh \
  modules/vision/src/test/resources/closebutton
```

Select one of the predefined screen regions with `--region` (the default is
`HALF_RIGHT`): `UPPER_LEFT_QUARTER`, `UPPER_RIGHT_QUARTER`,
`LOWER_LEFT_QUARTER`, `LOWER_RIGHT_QUARTER`, `HALF_RIGHT`, `MIDDLE`, or
`FULL_SCREEN`. For example, `--region UPPER_RIGHT_QUARTER` checks the area used
by initialization; `MIDDLE` is the central half of the frame in both width and
height. Both annotation and benchmark modes accept the region, so
the same saved frames can compare area coverage and runtime. Annotated boxes
and returned detection coordinates are relative to the full screen. The API
also accepts an explicit inclusive `AreaData` when a caller needs a custom
rectangle. Annotated output is written to
`tools/close-cross-detection/target/detections`, which stays out of git.

Benchmark one or more images without writing annotations:

```sh
./tools/close-cross-detection/detect.sh --do-benchmark --passes 100 \
  modules/vision/src/test/resources/closebutton
```

Choose a specific region for annotation or timing with `--region`, for example:

```sh
./tools/close-cross-detection/detect.sh --region UPPER_RIGHT_QUARTER \
  --do-benchmark --passes 100 modules/vision/src/test/resources/closebutton
```

The tool shares image loading, output naming, annotation drawing, and benchmark
timing with `tools/life-essence-detection`. Close-cross validation uses
manually reviewed anonymized crops; the original screenshots remain in the
local `.garbage` work area and are not test resources.

Annotated saved-frame evidence:

- [Offer overlay, top right](evidence/offer-top-right.png)
- [Blue control, top right](evidence/blue-top-right.png)
- [Orange control, top right](evidence/orange-top-right.png)
- [Tips dialog, middle right](evidence/middle-right.png)
- [Green plus control, rejected](evidence/green-plus-negative.png)

The 55 percent OpenCV match threshold is an empirical starting point for the
four saved cross styles in the standard 720 x 1280 viewport. Detection alone
does not prove that a dialog is safe to close. Consumers must choose a suitable
region or custom area and decide whether to act on the returned location.
