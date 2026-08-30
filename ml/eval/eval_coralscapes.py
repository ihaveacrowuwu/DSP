"""Coralscapes test-split domain-gap evaluation.

Pre-registered method, fixed before any results were seen:
- Deployed pipeline unchanged: PATCH_GRID=5, PATCH_OVERLAP=0, BLEACHED_LABEL_THRESHOLD=0.35.
- Bleached mask classes: {4,16,19,33}. Alive-coral classes: {6,17,21,22,25,27,28,31,34,36}.
- The service tiles only the centre square, so all ground truth is computed on the centre square.
- Image-level GT: bleached if bleached pixels >= 5% of centre square (sensitivity cuts 1% and 10% reported too).
- Patch-level GT: same 5x5 tiling of the centre square; a cell is scored only if coral
  (alive+bleached) >= 10% of its pixels; GT = bleached if bleached > alive pixels, else healthy.
- Metrics: image confusion + precision/recall/F2-bleached per cut; Spearman/Pearson of model
  severity vs centre bleached fraction; patch confusion over scored cells.
"""
import io, json, time, urllib.request, uuid
import numpy as np
import pyarrow.parquet as pq
from PIL import Image
from huggingface_hub import hf_hub_download

ML = "http://localhost:8010/classify"
BLEACHED = np.array([4, 16, 19, 33])
ALIVE = np.array([6, 17, 21, 22, 25, 27, 28, 31, 34, 36])
GRID = 5

def classify(jpg_bytes):
    bnd = uuid.uuid4().hex
    body = (f'--{bnd}\r\nContent-Disposition: form-data; name="file"; filename="eval.jpg"\r\n'
            f'Content-Type: image/jpeg\r\n\r\n').encode() + jpg_bytes + f"\r\n--{bnd}--\r\n".encode()
    req = urllib.request.Request(ML, data=body, method="POST",
        headers={"Content-Type": f"multipart/form-data; boundary={bnd}"})
    with urllib.request.urlopen(req, timeout=120) as r:
        return json.loads(r.read())

def centre_square(lab):
    h, w = lab.shape[:2]
    side = min(h, w); x0 = (w - side) // 2; y0 = (h - side) // 2
    return lab[y0:y0+side, x0:x0+side]

def cell_gt(sq):
    side = sq.shape[0]; step = side / GRID
    out = []
    for row in range(GRID):
        for col in range(GRID):
            cell = sq[int(row*step):int((row+1)*step), int(col*step):int((col+1)*step)]
            n = cell.size
            b = np.isin(cell, BLEACHED).sum() / n
            a = np.isin(cell, ALIVE).sum() / n
            gt = None if (b + a) < 0.10 else ("bleached" if b > a else "healthy")
            out.append({"row": row, "col": col, "gt": gt, "b": round(float(b),4), "a": round(float(a),4)})
    return out

files = [hf_hub_download("EPFL-ECEO/coralscapes", f"data/test-0000{i}-of-00003.parquet", repo_type="dataset") for i in range(3)]
results = []
t0 = time.time()
for fi, f in enumerate(files):
    pf = pq.ParquetFile(f)
    row = 0
    for batch in pf.iter_batches(batch_size=4):
        for i in range(batch.num_rows):
            img_bytes = batch.column("image")[i]["bytes"].as_py()
            lab = np.asarray(Image.open(io.BytesIO(batch.column("label")[i]["bytes"].as_py())))
            sq = centre_square(lab)
            bfrac = float(np.isin(sq, BLEACHED).sum()) / sq.size
            afrac = float(np.isin(sq, ALIVE).sum()) / sq.size
            # re-encode PNG -> JPEG q92, matching how a stored upload reaches the model
            img = Image.open(io.BytesIO(img_bytes)).convert("RGB")
            buf = io.BytesIO(); img.save(buf, "JPEG", quality=92)
            pred = classify(buf.getvalue())
            results.append({
                "id": f"p{fi}r{row:03d}", "centre_bleached_frac": round(bfrac,4),
                "centre_alive_frac": round(afrac,4),
                "model_label": pred["label"], "model_severity": pred["severity"],
                "model_confidence": pred["confidence"], "inference_ms": pred["inference_ms"],
                "patches_pred": [p["label"] for p in pred["patches"]],
                "patches_gt": cell_gt(sq),
            })
            row += 1
            done = len(results)
            if done % 25 == 0:
                print(f"{done} images, {time.time()-t0:.0f}s elapsed", flush=True)

with open("/Users/nauhaan/Documents/GitHub/DSP/ml/datasets/coralscapes/eval/results.json", "w") as fh:
    json.dump({"config": {"grid": GRID, "overlap": 0, "threshold": 0.35,
               "model": "effnetb0-0.1.0", "gt_cut_primary": 0.05},
               "n": len(results), "results": results}, fh)
print(f"DONE: {len(results)} images in {time.time()-t0:.0f}s", flush=True)
