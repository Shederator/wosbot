# Chat reader models

The weights the in-process chat reader runs. PP-OCRv5, converted from PaddlePaddle to ONNX.

| File | What it does |
|---|---|
| `det.onnx` | Finds where the text is. One detector for every language. |
| `rec_<alphabet>.onnx` | Turns one cropped line into characters. One per alphabet. |
| `rec_<alphabet>.dict` | The characters that model is able to emit, in the order it emits them. |

A recogniser and its dictionary are a pair. Swapping one without the other does not fail — it
produces confident nonsense, because the network still answers with indices and the indices now
mean different letters.

## Why these are committed

They are binaries, and committing binaries is normally the wrong instinct. They are here anyway
because the alternative was worse: with the weights excluded, anyone who cloned this and turned
chat capture on got a transcript read by the fallback while the settings screen said otherwise.
Every accuracy figure that has been measured is the in-process reader's, so a build that silently
used something else was not testable at all.

They are stored through Git LFS, which is what this repository already does with Tesseract's
`.traineddata`. At about 91 MB the whole set is a third of the Tesseract data already shipped
beside it.

Only `det.onnx`, `rec_latin.onnx` and `rec_latin.dict` are needed for the reader to start. The rest
are loaded when a message turns up in that script.

## Where they end up

| Context | Path |
|---|---|
| Working from a checkout | `tools/ocr/onnx` |
| Installed | `lib/ocr-models` |

`ChatCaptureRoutine.onnxDir()` walks up from the working directory looking for either, the same way
`TesseractOcrProvider` finds its own data. The installer stages this folder in `packaging/desktop`,
in the `stage-onnx-models` execution.

## Rebuilding them

`tools/ocr/convert-venv` is a throwaway Python environment used to run the conversion; it is not
committed. The dictionaries are extracted from each Paddle model's `config.json`, out of
`PostProcess.character_dict` — they are not separate downloads, and a dictionary rebuilt from
anywhere else will not line up with the weights.

`check.py` reads one saved frame with the models directly. It is the fastest way to tell whether a
converted model is sound before wiring it into anything.
