"""Exports MobileCLIP2-S0 to ONNX for the Android apps.

Run in CI (see .github/workflows/build.yml); needs internet to fetch the weights from Hugging Face.

Outputs, under --out (default: model-out/):
  assets/clip/image_encoder.onnx  fp32. pixel_values [N,3,256,256] float in [0,1] -> embedding [N,512], L2-normalised
  assets/clip/text_encoder.onnx   int8 weights. input_ids [N,77] int64 -> embedding [N,512], L2-normalised
  assets/clip/clip_config.json    preprocessing + tokenizer constants read by the app
  fixtures/clip_fixtures.json     reference outputs for core/engine's ClipModelParityTest

MobileCLIP2 weights: Apple Machine Learning Research Model License (research / non-commercial).
"""
import argparse
import json
import os

import numpy as np
import onnx
import onnxruntime as ort
import open_clip
import torch
from onnxruntime.quantization import QuantType, quantize_dynamic

MODEL = "MobileCLIP2-S0"
PRETRAINED = "dfndr2b"

PARITY_TEXTS = [
    "car",
    "a photo of a dog",
    "a still frame from a movie",
    "a screenshot of a chat conversation in a messaging app",
    "a UPI payment receipt",
    "Movie ticket for Interstellar",
]


def reparameterize(model):
    try:
        from timm.utils.model import reparameterize_model
    except ImportError:
        from timm.utils import reparameterize_model
    return reparameterize_model(model)


class ImageEncoder(torch.nn.Module):
    def __init__(self, model):
        super().__init__()
        self.model = model

    def forward(self, pixel_values):
        f = self.model.encode_image(pixel_values)
        return f / f.norm(dim=-1, keepdim=True)


class TextEncoder(torch.nn.Module):
    def __init__(self, model):
        super().__init__()
        self.model = model

    def forward(self, input_ids):
        f = self.model.encode_text(input_ids)
        return f / f.norm(dim=-1, keepdim=True)


def synthetic_image(size):
    """Deterministic RGB pattern; core/engine's parity test draws the same pixels."""
    y, x = np.mgrid[0:size, 0:size]
    r = x * 255 // (size - 1)
    g = y * 255 // (size - 1)
    b = ((x // 16 + y // 16) % 2) * 200 + 27
    return np.stack([r, g, b], axis=0).astype(np.float32) / 255.0  # CHW in [0,1]


def cosine(a, b):
    a = np.asarray(a, dtype=np.float64)
    b = np.asarray(b, dtype=np.float64)
    return float(np.dot(a, b) / (np.linalg.norm(a) * np.linalg.norm(b)))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="model-out")
    args = ap.parse_args()

    clip_dir = os.path.join(args.out, "assets", "clip")
    fixtures_dir = os.path.join(args.out, "fixtures")
    os.makedirs(clip_dir, exist_ok=True)
    os.makedirs(fixtures_dir, exist_ok=True)

    torch.manual_seed(0)
    # nn.MultiheadAttention's inference fast path (aten::_native_multi_head_attention) has no ONNX export.
    torch.backends.mha.set_fastpath_enabled(False)
    model, _, _ = open_clip.create_model_and_transforms(MODEL, pretrained=PRETRAINED)
    model.eval()
    model = reparameterize(model)
    tokenizer = open_clip.get_tokenizer(MODEL)
    assert type(tokenizer).__name__ == "SimpleTokenizer", f"unexpected tokenizer {type(tokenizer)}"

    pp = model.visual.preprocess_cfg
    size = pp["size"][0] if isinstance(pp["size"], (list, tuple)) else pp["size"]
    config = {
        "model": MODEL,
        "pretrained": PRETRAINED,
        "image_size": int(size),
        "mean": [float(v) for v in pp["mean"]],
        "std": [float(v) for v in pp["std"]],
        "interpolation": pp.get("interpolation", "bilinear"),
        "resize_mode": pp.get("resize_mode", "shortest"),
        "context_length": int(tokenizer.context_length),
        "sot_token_id": int(tokenizer.sot_token_id),
        "eot_token_id": int(tokenizer.eot_token_id),
        "logit_scale": float(model.logit_scale.exp().item()),
    }
    with torch.no_grad():
        config["embed_dim"] = int(ImageEncoder(model)(torch.zeros(1, 3, config["image_size"], config["image_size"])).shape[-1])
    print("config:", config)

    image_fp32 = os.path.join(clip_dir, "image_encoder.onnx")
    text_fp32 = os.path.join(args.out, "text_encoder_fp32.onnx")
    text_int8 = os.path.join(clip_dir, "text_encoder.onnx")

    dummy_image = torch.zeros(1, 3, config["image_size"], config["image_size"])
    dummy_text = tokenizer(["a photo of a dog"])

    with torch.no_grad():
        torch.onnx.export(
            ImageEncoder(model), dummy_image, image_fp32,
            input_names=["pixel_values"], output_names=["embedding"],
            dynamic_axes={"pixel_values": {0: "batch"}, "embedding": {0: "batch"}},
            opset_version=17, do_constant_folding=True, dynamo=False,
        )
        torch.onnx.export(
            TextEncoder(model), dummy_text, text_fp32,
            input_names=["input_ids"], output_names=["embedding"],
            dynamic_axes={"input_ids": {0: "batch"}, "embedding": {0: "batch"}},
            opset_version=17, do_constant_folding=True, dynamo=False,
        )
    onnx.checker.check_model(image_fp32)
    onnx.checker.check_model(text_fp32)

    quantize_dynamic(
        text_fp32, text_int8,
        weight_type=QuantType.QInt8,
        op_types_to_quantize=["MatMul", "Gather"],
    )

    # ---- Parity: PyTorch vs ONNX, and reference outputs for the Kotlin test ----
    img = synthetic_image(config["image_size"])
    tokens = tokenizer(PARITY_TEXTS)
    with torch.no_grad():
        torch_img = ImageEncoder(model)(torch.from_numpy(img)[None]).numpy()[0]
        torch_txt = TextEncoder(model)(tokens).numpy()

    so = ort.SessionOptions()
    img_sess = ort.InferenceSession(image_fp32, so, providers=["CPUExecutionProvider"])
    txt_sess = ort.InferenceSession(text_int8, so, providers=["CPUExecutionProvider"])
    onnx_img = img_sess.run(None, {"pixel_values": img[None]})[0][0]
    onnx_txt = txt_sess.run(None, {"input_ids": tokens.numpy().astype(np.int64)})[0]

    img_cos = cosine(torch_img, onnx_img)
    txt_cos = [cosine(a, b) for a, b in zip(torch_txt, onnx_txt)]
    print(f"image fp32 parity cos={img_cos:.6f}")
    print("text int8 parity cos=", ["%.4f" % c for c in txt_cos])
    assert img_cos > 0.999, img_cos
    assert min(txt_cos) > 0.98, txt_cos

    # Zero-shot sanity on the synthetic pattern is meaningless; check text geometry instead:
    # related prompts should be closer than unrelated ones.
    sim = onnx_txt @ onnx_txt.T
    print("text sim matrix:\n", np.round(sim, 3))

    fixtures = {
        "image_size": config["image_size"],
        "image_embedding": [float(v) for v in onnx_img],
        "texts": [
            {"text": t, "ids": [int(v) for v in ids], "embedding": [float(v) for v in emb]}
            for t, ids, emb in zip(PARITY_TEXTS, tokens.numpy(), onnx_txt)
        ],
    }
    with open(os.path.join(fixtures_dir, "clip_fixtures.json"), "w") as f:
        json.dump(fixtures, f)
    with open(os.path.join(clip_dir, "clip_config.json"), "w") as f:
        json.dump(config, f, indent=2)

    os.remove(text_fp32)
    for name in os.listdir(clip_dir):
        print(f"{name}: {os.path.getsize(os.path.join(clip_dir, name)) / 1e6:.1f} MB")


if __name__ == "__main__":
    main()
