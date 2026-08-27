"""Reads one saved chat frame with the ONNX models and prints what they say.

This exists to answer one question before any Java is written: do the converted models, driven by a
pipeline we control, produce the same text as the Paddle service does today. Everything downstream
depends on that being yes, and it is far cheaper to find out here than after porting.

The pipeline is the standard PP-OCR one, written out longhand so the Java port has something exact
to follow: resize to a multiple of 32, normalise, run detection, threshold the probability map into
boxes, expand each box a little, crop, resize each crop to 48 pixels tall, run recognition, and
collapse the per-timestep argmax into text.
"""
import sys

import cv2
import numpy as np
import onnxruntime as ort

HERE = "tools/ocr/onnx"

# What the models were trained with. Getting these wrong does not fail loudly; it just reads badly.
MEAN = np.array([0.485, 0.456, 0.406], dtype=np.float32)
STD = np.array([0.229, 0.224, 0.225], dtype=np.float32)

# Detection thresholds, from PP-OCR's own defaults.
BOX_THRESHOLD = 0.3       # a pixel is text if the map says this much
BOX_SCORE_MIN = 0.5       # a box is kept if its mean probability clears this
UNCLIP = 1.6              # boxes come out tight around the ink; text needs a margin
REC_HEIGHT = 48
REC_WIDTH = 320


def load_dict(path):
    with open(path, encoding="utf-8") as f:
        chars = f.read().split("\n")
    if chars and chars[-1] == "":
        chars.pop()
    # Index 0 is CTC's blank; the dictionary fills 1..n, and anything unmapped reads as a space.
    return ["<blank>"] + chars + [" "]


def detect(session, image):
    h, w = image.shape[:2]
    # The network needs both sides to be multiples of 32.
    rh, rw = max(32, round(h / 32) * 32), max(32, round(w / 32) * 32)
    resized = cv2.resize(image, (rw, rh))
    x = (resized.astype(np.float32) / 255.0 - MEAN) / STD
    x = x.transpose(2, 0, 1)[None]
    prob = session.run(None, {session.get_inputs()[0].name: x})[0][0, 0]

    mask = (prob > BOX_THRESHOLD).astype(np.uint8)
    contours, _ = cv2.findContours(mask, cv2.RETR_LIST, cv2.CHAIN_APPROX_SIMPLE)
    boxes = []
    for contour in contours:
        rect = cv2.minAreaRect(contour)
        score = region_score(prob, cv2.boxPoints(rect))
        if score < BOX_SCORE_MIN:
            continue
        box = unclip(contour, rect, UNCLIP)
        if box is None:
            continue
        box[:, 0] *= w / rw
        box[:, 1] *= h / rh
        boxes.append((order(box), score))
    return boxes


def region_score(prob, box):
    """Mean probability inside a box -- how sure the detector is that this is text."""
    h, w = prob.shape
    x0, x1 = int(max(0, box[:, 0].min())), int(min(w - 1, box[:, 0].max()))
    y0, y1 = int(max(0, box[:, 1].min())), int(min(h - 1, box[:, 1].max()))
    if x1 <= x0 or y1 <= y0:
        return 0.0
    patch = prob[y0:y1 + 1, x0:x1 + 1]
    mask = np.zeros(patch.shape, dtype=np.uint8)
    shifted = box.copy()
    shifted[:, 0] -= x0
    shifted[:, 1] -= y0
    cv2.fillPoly(mask, [shifted.astype(np.int32)], 1)
    return float(cv2.mean(patch, mask)[0])


def unclip(contour, rect, ratio):
    """Pushes a box outward by a fixed distance, the way PP-OCR does.

    Not a proportional scale. Scaling a box from its centre grows it as much along its length as
    across it, so a line of text three hundred pixels wide gains a hundred and eighty pixels of
    width -- it swallows whatever is beside it, and the recogniser is handed two lines at once.
    What is wanted is a margin of constant thickness, which is area over perimeter times the ratio.
    """
    area = cv2.contourArea(contour)
    perimeter = cv2.arcLength(contour, True)
    if perimeter <= 0:
        return None
    distance = area * ratio / perimeter
    (cx, cy), (bw, bh), angle = rect
    grown = ((cx, cy), (bw + 2 * distance, bh + 2 * distance), angle)
    return cv2.boxPoints(grown).astype(np.float32)


def order(box):
    """Corners as top-left, top-right, bottom-right, bottom-left."""
    s = box.sum(axis=1)
    d = np.diff(box, axis=1).ravel()
    return np.array([box[np.argmin(s)], box[np.argmin(d)],
                     box[np.argmax(s)], box[np.argmax(d)]], dtype=np.float32)


def crop(image, box):
    """Straightens one box out of the frame."""
    w = int(max(np.linalg.norm(box[0] - box[1]), np.linalg.norm(box[2] - box[3])))
    h = int(max(np.linalg.norm(box[0] - box[3]), np.linalg.norm(box[1] - box[2])))
    if w < 4 or h < 4:
        return None
    target = np.array([[0, 0], [w, 0], [w, h], [0, h]], dtype=np.float32)
    return cv2.warpPerspective(image, cv2.getPerspectiveTransform(box, target), (w, h))


def recognise(session, chars, patch):
    h, w = patch.shape[:2]
    # Height fixed, width scaled to match, then padded out to a fixed canvas. Feeding the network a
    # bare resize instead -- which is the obvious thing to write -- costs it the spaces between
    # words and drops characters: it was trained on padded input and the padding is part of what it
    # expects to see.
    target = max(8, int(round(w * REC_HEIGHT / h)))
    # The canvas grows to fit rather than capping at the nominal width. Squeezing a long line down
    # to three hundred and twenty pixels costs it letters -- "seria bueno que ahora" came back as
    # "sera buen que ahra", each missing character a stroke that fell between two output steps.
    canvas_w = max(REC_WIDTH, (target + 7) // 8 * 8)
    scaled = cv2.resize(patch, (target, REC_HEIGHT))
    canvas = np.zeros((REC_HEIGHT, canvas_w, 3), dtype=np.uint8)
    canvas[:, :target] = scaled
    x = (canvas.astype(np.float32) / 255.0 - 0.5) / 0.5
    x = x.transpose(2, 0, 1)[None]
    out = session.run(None, {session.get_inputs()[0].name: x})[0][0]

    # CTC: take the best character at each step, drop repeats, drop blanks.
    best = out.argmax(axis=1)
    conf = out.max(axis=1)
    text, kept, previous = [], [], -1
    for i, c in enumerate(best):
        if c != previous and c != 0:
            if c < len(chars):
                text.append(chars[c])
                kept.append(conf[i])
        previous = c
    return "".join(text), float(np.mean(kept)) if kept else 0.0


def main(frame_path, left, top, right, bottom):
    frame = cv2.imread(frame_path)
    region = frame[top:bottom, left:right]
    det = ort.InferenceSession("%s/det.onnx" % HERE, providers=["CPUExecutionProvider"])
    rec = ort.InferenceSession("%s/rec_latin.onnx" % HERE, providers=["CPUExecutionProvider"])
    chars = load_dict("%s/rec_latin.dict" % HERE)

    boxes = detect(det, region)
    boxes.sort(key=lambda b: b[0][:, 1].min())
    out = open("tools/ocr/onnx/check_out.txt", "w", encoding="utf-8")
    for box, _ in boxes:
        patch = crop(region, box)
        if patch is None:
            continue
        text, conf = recognise(rec, chars, patch)
        if text.strip() and conf >= 0.5:
            out.write("%.2f  y=%4d  %s\n" % (conf, int(box[:, 1].min()) + top, text))
    out.close()
    print("wrote tools/ocr/onnx/check_out.txt (%d boxes detected)" % len(boxes))


if __name__ == "__main__":
    main(sys.argv[1], int(sys.argv[2]), int(sys.argv[3]), int(sys.argv[4]), int(sys.argv[5]))
