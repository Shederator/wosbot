# Life Essence detection overlay

Draws the current `LifeEssenceMarkerDetector` result on copies of island or
other screenshots. Accepted regions are green, rejected regions are red, the
cyan cross is the tap point, and the orange cross is the box center. The task
itself is unchanged.

Run it from the repository root:

```sh
./tools/life-essence-detection/detect.sh \
  modules/tasks/src/test/resources/live-regressions-20260922/life-essence-available-marker.png
```

A directory is walked for PNG files. `--output` selects the directory.
The default is `tools/life-essence-detection/target/detections`, which stays
out of git. Each file is named `timestamp-fixture_name-detection_result.png`.
The matching measurements are printed before that path. A negative leaf `dy`
means the green centroid is above the box center.

The first run compiles `modules/tasks` and the tool. Re-run it after a
detector change to compare the new picture with the previous one.

`--do-benchmark` measures detection only. Each image is read once, then
analyzed `--passes` times (default 1000). The console prints the mean time
per file and no annotated PNG is written.

```sh
./tools/life-essence-detection/detect.sh --do-benchmark --passes 1000 \
  modules/tasks/src/test/resources/live-regressions-20260922/life-essence-available-marker.png
```
