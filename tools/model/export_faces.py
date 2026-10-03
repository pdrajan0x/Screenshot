"""Fetches the face-recognition model used by Dot Gallery's People view.

MobileFaceNet (w600k_mbf.onnx) from InsightFace's buffalo_s pack: 112×112 aligned RGB face →
512-d embedding. InsightFace model weights are for non-commercial research use; Dot Gallery is
a personal, non-commercial app.

Outputs, under --out (default: model-out-faces/):
  assets/faces/face_embedder.onnx
  assets/faces/faces_config.json
"""
import argparse
import io
import json
import os
import urllib.request
import zipfile

import numpy as np
import onnx
import onnxruntime as ort

URL = "https://github.com/deepinsight/insightface/releases/download/v0.7/buffalo_s.zip"
MEMBER = "w600k_mbf.onnx"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="model-out-faces")
    args = ap.parse_args()
    out_dir = os.path.join(args.out, "assets", "faces")
    os.makedirs(out_dir, exist_ok=True)

    print("downloading", URL)
    data = urllib.request.urlopen(URL, timeout=120).read()
    with zipfile.ZipFile(io.BytesIO(data)) as z:
        name = next(n for n in z.namelist() if n.endswith(MEMBER))
        model_bytes = z.read(name)
    path = os.path.join(out_dir, "face_embedder.onnx")
    with open(path, "wb") as f:
        f.write(model_bytes)

    model = onnx.load(path)
    onnx.checker.check_model(model)
    inp = model.graph.input[0]
    shape = [d.dim_value for d in inp.type.tensor_type.shape.dim]
    print("input", inp.name, shape)

    sess = ort.InferenceSession(path, providers=["CPUExecutionProvider"])
    x = (np.random.RandomState(0).rand(1, 3, 112, 112).astype(np.float32) - 0.5) * 2
    y = sess.run(None, {inp.name: x})[0]
    print("output", y.shape)
    assert y.shape[-1] == 512, y.shape

    config = {
        "model": "insightface-w600k_mbf",
        "input_name": inp.name,
        "size": 112,
        "mean": 127.5,
        "std": 127.5,
        "channels": "RGB",
        "embed_dim": int(y.shape[-1]),
    }
    with open(os.path.join(out_dir, "faces_config.json"), "w") as f:
        json.dump(config, f, indent=2)
    print(f"face_embedder.onnx: {os.path.getsize(path) / 1e6:.1f} MB")


if __name__ == "__main__":
    main()
