"""Exports MobileCLIP2-S2 to ONNX for Dot Screenshots.

Run in CI (see .github/workflows/build.yml); needs internet to fetch the weights from Hugging Face.

Outputs, under --out (default: model-out/):
  assets/clip/image_encoder.onnx  weights stored as fp16, run as fp32. pixel_values [N,3,256,256] float in [0,1] -> embedding [N,512], L2-normalised
  assets/clip/text_encoder.onnx   int8 weights. input_ids [N,77] int64 -> embedding [N,512], L2-normalised
  assets/clip/clip_config.json    preprocessing + tokenizer constants read by the app
  fixtures/clip_fixtures.json     reference outputs for core/engine's ClipModelParityTest

MobileCLIP2 weights: Apple Machine Learning Research Model License (research / non-commercial).
"""
import argparse
import copy
import json
import os
import shutil

import numpy as np
import onnx
import onnxruntime as ort
import open_clip
import torch
from onnxruntime.quantization import QuantType, quantize_dynamic

# S2 over S0: on 86 labelled photos the app's keyword rule got 71 right (1 wrong) vs 62 (1 wrong),
# for ~2.6x the image time; B got 72 for another ~2.8x (tools/model/compare_clip.py).
MODEL = "MobileCLIP2-S2"
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


def l2_normalize(f):
    # Written with ReduceSum rather than torch.norm (ReduceL2): the quantiser upgrades the opset,
    # and an opset-17 ReduceL2's `axes` attribute is invalid in newer opsets.
    return f / torch.sqrt((f * f).sum(dim=-1, keepdim=True))


class ImageEncoder(torch.nn.Module):
    def __init__(self, model):
        super().__init__()
        self.model = model

    def forward(self, pixel_values):
        return l2_normalize(self.model.encode_image(pixel_values))


class TextEncoder(torch.nn.Module):
    def __init__(self, model):
        super().__init__()
        self.model = model

    def forward(self, input_ids):
        return l2_normalize(self.model.encode_text(input_ids))


def synthetic_image(size, seed=0):
    """Deterministic RGB pattern; core/engine's parity test draws the same pixels (seed 0).
    Other seeds add smooth noise, for checking an export on more than one picture."""
    y, x = np.mgrid[0:size, 0:size]
    r = x * 255 // (size - 1)
    g = y * 255 // (size - 1)
    b = ((x // 16 + y // 16) % 2) * 200 + 27
    img = np.stack([r, g, b], axis=0).astype(np.float32) / 255.0  # CHW in [0,1]
    if seed:
        rng = np.random.default_rng(seed)
        noise = rng.random((3, size // 16, size // 16)).astype(np.float32)
        img = np.clip(0.5 * img + 0.5 * noise.repeat(16, axis=1).repeat(16, axis=2), 0, 1)
    return img


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
    raw_model, _, _ = open_clip.create_model_and_transforms(MODEL, pretrained=PRETRAINED)
    raw_model.eval()
    model = reparameterize(copy.deepcopy(raw_model))
    model.eval()
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

    img = synthetic_image(config["image_size"])
    example_image = torch.from_numpy(img)[None]
    tokens = tokenizer(PARITY_TEXTS)
    with torch.no_grad():
        torch_img = ImageEncoder(model)(example_image).numpy()[0]
        torch_img_raw = ImageEncoder(raw_model)(example_image).numpy()[0]
        torch_txt = TextEncoder(model)(tokens).numpy()
        traced = torch.jit.trace(ImageEncoder(model), example_image)
        traced_img = traced(example_image).numpy()[0]
        # Reference outputs for more pictures, taken now: after torch.onnx.export the in-memory
        # model's outputs no longer match (the exported graph does, see the checks below).
        checks = [synthetic_image(config["image_size"], seed) for seed in range(4)]
        check_refs = [ImageEncoder(model)(torch.from_numpy(x)[None]).numpy()[0] for x in checks]
    print(f"diag: reparam vs original torch cos={cosine(torch_img, torch_img_raw):.6f}")
    print(f"diag: jit.trace vs eager cos={cosine(torch_img, traced_img):.6f}")

    work = os.path.join(args.out, "work")
    os.makedirs(work, exist_ok=True)

    # ---- Image encoder: try export variants x ORT optimisation levels, keep the first exact one ----
    image_variants = [
        ("reparam-fold", model, dict(do_constant_folding=True, dynamo=False)),
        ("reparam-nofold", model, dict(do_constant_folding=False, dynamo=False)),
        ("original-fold", raw_model, dict(do_constant_folding=True, dynamo=False)),
        ("reparam-dynamo", model, dict(dynamo=True)),
    ]
    chosen_image = None
    for name, m, kwargs in image_variants:
        path = os.path.join(work, f"image-{name}.onnx")
        try:
            with torch.no_grad():
                export_kwargs = dict(input_names=["pixel_values"], output_names=["embedding"], opset_version=17, **kwargs)
                if kwargs.get("dynamo"):
                    export_kwargs["dynamic_shapes"] = {"pixel_values": {0: torch.export.Dim("batch", min=1, max=8)}}
                    export_kwargs["opset_version"] = 18
                else:
                    export_kwargs["dynamic_axes"] = {"pixel_values": {0: "batch"}, "embedding": {0: "batch"}}
                torch.onnx.export(ImageEncoder(m), example_image, path, **export_kwargs)
        except Exception as e:  # noqa: BLE001
            print(f"image {name}: export failed: {type(e).__name__}: {e}"[:400])
            continue
        reference = torch_img if m is model else torch_img_raw
        for level in LEVELS:
            try:
                out = ort_run(path, {"pixel_values": img[None]}, level)[0]
                c = cosine(reference, out)
            except Exception as e:  # noqa: BLE001
                print(f"image {name} @ {level}: run failed: {e}"[:300])
                continue
            print(f"image {name} @ {level}: cos={c:.6f}")
            if c > 0.999 and chosen_image is None:
                chosen_image = (name, path, level)
        if chosen_image:
            break
    assert chosen_image, "no image export variant matched PyTorch"
    print("chosen image variant:", chosen_image)
    # Store the weights as fp16 (half the APK size); ORT folds the casts back to fp32 when the
    # session is created, so the arithmetic is unchanged. Kept only if it still matches PyTorch.
    half_path = os.path.join(work, "image-fp16-weights.onnx")
    try:
        fp16_weights(chosen_image[1], half_path)
        full = [cosine(r, ort_run(chosen_image[1], {"pixel_values": x[None]}, chosen_image[2])[0]) for r, x in zip(check_refs, checks)]
        half = [cosine(r, ort_run(half_path, {"pixel_values": x[None]}, chosen_image[2])[0]) for r, x in zip(check_refs, checks)]
        print("image fp32 export vs torch per picture:", [round(v, 6) for v in full])
        print("image fp16 weights vs torch per picture:", [round(v, 6) for v in half])
        c = min(half)
        print(f"image fp16 weights: min cos={c:.6f} size={os.path.getsize(half_path) / 1e6:.1f} MB")
        assert min(full) > 0.999, "the fp32 image export doesn't match PyTorch on every check picture"
        if c > 0.999:
            chosen_image = (chosen_image[0] + "+fp16-weights", half_path, chosen_image[2])
    except Exception as e:  # noqa: BLE001
        print(f"image fp16 weights: failed: {type(e).__name__}: {e}"[:400])
    image_out = os.path.join(clip_dir, "image_encoder.onnx")
    shutil.copy(chosen_image[1], image_out)

    # ---- Text encoder: fp32 export, then the smallest quantisation that stays faithful ----
    text_fp32 = os.path.join(work, "text-fp32.onnx")
    with torch.no_grad():
        torch.onnx.export(
            TextEncoder(model), tokens, text_fp32,
            input_names=["input_ids"], output_names=["embedding"],
            dynamic_axes={"input_ids": {0: "batch"}, "embedding": {0: "batch"}},
            opset_version=17, do_constant_folding=True, dynamo=False,
        )
    feeds = {"input_ids": tokens.numpy().astype(np.int64)}

    def text_cos(path, level):
        out = ort_run(path, feeds, level)
        return min(cosine(a, b) for a, b in zip(torch_txt, out))

    text_level = None
    for level in LEVELS:
        c = text_cos(text_fp32, level)
        print(f"text fp32 @ {level}: min cos={c:.6f}")
        if c > 0.999 and text_level is None:
            text_level = level
    assert text_level, "text fp32 export does not match PyTorch"

    text_candidates = []
    # Weight-only quantisation (activations stay fp32) first: it keeps CLIP's text geometry,
    # whereas dynamic int8 (activations quantised too) measured ~0.95 cosine.
    quant_variants = [
        ("nbits8-matmul+nbits4-gather", lambda src, dst: nbits_mixed(src, dst, work)),
        ("nbits4-matmul-gather", lambda src, dst: nbits_quantize(src, dst, bits=4, ops=("MatMul", "Gather"))),
        ("nbits8-matmul-gather", lambda src, dst: nbits_quantize(src, dst, bits=8, ops=("MatMul", "Gather"))),
        ("nbits4-matmul", lambda src, dst: nbits_quantize(src, dst, bits=4, ops=("MatMul",))),
        ("nbits8-matmul", lambda src, dst: nbits_quantize(src, dst, bits=8, ops=("MatMul",))),
        ("int8-dynamic-gather", lambda src, dst: quantize_dynamic(src, dst, weight_type=QuantType.QInt8, op_types_to_quantize=["MatMul", "Gather"], per_channel=True)),
    ]
    for name, fn in quant_variants:
        path = os.path.join(work, f"text-{name}.onnx")
        try:
            fn(text_fp32, path)
            c = text_cos(path, text_level)
        except Exception as e:  # noqa: BLE001
            print(f"text {name}: failed: {type(e).__name__}: {e}"[:400])
            continue
        size = os.path.getsize(path)
        print(f"text {name}: min cos={c:.5f} size={size / 1e6:.1f} MB")
        if c >= 0.985:
            text_candidates.append((size, name, path, c))
    if not text_candidates:
        try:
            from onnxruntime.transformers.float16 import convert_float_to_float16
            path = os.path.join(work, "text-fp16.onnx")
            onnx.save(convert_float_to_float16(onnx.load(text_fp32), keep_io_types=True), path)
            c = text_cos(path, text_level)
            print(f"text fp16: min cos={c:.5f} size={os.path.getsize(path) / 1e6:.1f} MB")
            if c >= 0.985:
                text_candidates.append((os.path.getsize(path), "fp16", path, c))
        except Exception as e:  # noqa: BLE001
            print(f"text fp16: failed: {e}"[:300])
    if not text_candidates:
        text_candidates.append((os.path.getsize(text_fp32), "fp32", text_fp32, 1.0))
    # Smallest variant that is near-exact and reasonably small; otherwise accept a little drift
    # (>= 0.985 cosine barely changes retrieval order) to save ~100 MB; otherwise the smallest exact one.
    text_candidates.sort()
    pick = next((c for c in text_candidates if c[3] >= 0.995 and c[0] <= 100e6), None) \
        or next((c for c in text_candidates if c[3] >= 0.985 and c[0] <= 100e6), None) \
        or next((c for c in text_candidates if c[3] >= 0.995), None) \
        or text_candidates[0]
    _, text_name, text_path, _ = pick
    print("chosen text variant:", text_name)
    text_out = os.path.join(clip_dir, "text_encoder.onnx")
    shutil.copy(text_path, text_out)

    config["ort_optimization"] = {"image": chosen_image[2], "text": text_level}
    config["variants"] = {"image": chosen_image[0], "text": text_name}

    # ---- Reference outputs for the Kotlin parity test (same files + levels the app uses) ----
    onnx_img = ort_run(image_out, {"pixel_values": img[None]}, chosen_image[2])[0]
    onnx_txt = ort_run(text_out, feeds, text_level)
    print("text sim matrix:\n", np.round(onnx_txt @ onnx_txt.T, 3))

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

    shutil.rmtree(work)
    for name in sorted(os.listdir(clip_dir)):
        print(f"{name}: {os.path.getsize(os.path.join(clip_dir, name)) / 1e6:.1f} MB")


def fp16_weights(src, dst, min_elements=1024, max_rel_error=1e-3):
    """Large fp32 weights stored as fp16, each behind a Cast back to fp32 (constant-folded by ORT).
    A tensor fp16 can't hold faithfully (out of range, or tiny values that underflow) stays fp32."""
    from onnx import TensorProto, helper, numpy_helper

    m = onnx.load(src)
    g = m.graph
    inputs = {i.name for i in g.input}
    halves, casts, kept = [], [], []
    for init in list(g.initializer):
        if init.data_type != TensorProto.FLOAT or int(np.prod(init.dims)) < min_elements or init.name in inputs:
            continue
        w = numpy_helper.to_array(init)
        h = w.astype(np.float16)
        err = float(np.linalg.norm(w - h.astype(np.float32)) / (np.linalg.norm(w) + 1e-12))
        if not np.all(np.isfinite(h)) or err > max_rel_error:
            kept.append((init.name, err, float(np.abs(w).max())))
            continue
        half = numpy_helper.from_array(h, init.name + "__fp16")
        g.initializer.remove(init)
        halves.append(half)
        casts.append(helper.make_node("Cast", [half.name], [init.name], to=TensorProto.FLOAT, name=init.name + "__cast"))
    g.initializer.extend(halves)
    nodes = list(g.node)
    del g.node[:]
    g.node.extend(casts + nodes)
    onnx.checker.check_model(m)
    onnx.save(m, dst)
    print(f"fp16 weights: {len(halves)} tensors halved, {len(kept)} kept fp32 " + str([(n[:40], round(e, 4), round(a, 1)) for n, e, a in kept[:8]]))


def nbits_quantize(src, dst, bits, ops):
    """Blockwise weight-only quantisation (MatMulNBits / GatherBlockQuantized contrib ops)."""
    import inspect
    from onnxruntime.quantization import matmul_nbits_quantizer as mnq

    quantizer_params = inspect.signature(mnq.MatMulNBitsQuantizer.__init__).parameters
    kwargs = {"block_size": 32, "is_symmetric": True}
    if "bits" in quantizer_params:
        kwargs["bits"] = bits
    elif bits != 4:
        raise RuntimeError("this onnxruntime only supports 4-bit MatMulNBits")
    if "op_types_to_quantize" in quantizer_params:
        kwargs["op_types_to_quantize"] = tuple(ops)
    elif "Gather" in ops:
        raise RuntimeError("this onnxruntime can't quantise Gather")
    if hasattr(mnq, "DefaultWeightOnlyQuantConfig") and "algo_config" in quantizer_params:
        cfg_params = inspect.signature(mnq.DefaultWeightOnlyQuantConfig.__init__).parameters
        cfg_kwargs = {k: v for k, v in kwargs.items() if k in cfg_params}
        kwargs["algo_config"] = mnq.DefaultWeightOnlyQuantConfig(**cfg_kwargs)
    quantizer = mnq.MatMulNBitsQuantizer(onnx.load(src), **kwargs)
    quantizer.process()
    quantizer.model.save_model_to_file(dst, use_external_data_format=False)


def nbits_mixed(src, dst, work):
    """8-bit weight-only MatMuls (accuracy) + 4-bit token-embedding Gather (size)."""
    tmp = os.path.join(work, "text-nbits8-tmp.onnx")
    nbits_quantize(src, tmp, bits=8, ops=("MatMul",))
    nbits_quantize(tmp, dst, bits=4, ops=("Gather",))


LEVELS = ["all", "extended", "basic", "none"]


def ort_run(path, feeds, level):
    so = ort.SessionOptions()
    so.graph_optimization_level = {
        "all": ort.GraphOptimizationLevel.ORT_ENABLE_ALL,
        "extended": ort.GraphOptimizationLevel.ORT_ENABLE_EXTENDED,
        "basic": ort.GraphOptimizationLevel.ORT_ENABLE_BASIC,
        "none": ort.GraphOptimizationLevel.ORT_DISABLE_ALL,
    }[level]
    sess = ort.InferenceSession(path, so, providers=["CPUExecutionProvider"])
    return sess.run(None, feeds)[0]


if __name__ == "__main__":
    main()
