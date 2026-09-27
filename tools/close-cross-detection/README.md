# Close-cross detection

This tool runs the reusable `CloseCrossDetector` against saved PNG frames. It
draws a green box around each accepted match and a cyan cross at the returned
center. The tool does not tap or alter the input image.

Run it from the repository root:

```sh
./tools/close-cross-detection/detect.sh \
  modules/vision/src/test/resources/closebutton
```

The default search covers the right half of the frame. The detector API also
accepts a caller-supplied area. Annotated output is written to
`tools/close-cross-detection/target/detections`, which stays out of git.

Benchmark one or more images without writing annotations:

```sh
./tools/close-cross-detection/detect.sh --do-benchmark --passes 100 \
  modules/vision/src/test/resources/closebutton
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
does not prove that a dialog is safe to close. Consumers must constrain the
search area and decide whether to act on the returned location.
