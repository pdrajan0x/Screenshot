#!/usr/bin/env python3
"""
Fetches Florence-2-base (Microsoft, MIT licence) as ONNX for the app's screenshot descriptions.

Picked over same-size models (Florence-2-base-ft, SmolVLM-256M, SmolVLM2-256M, ViT-GPT2) on real
screenshots: the most accurate descriptions of the pictures in them, at ~2 s per screenshot on a
4-core CPU, 215 MB with 4-bit weights.

Writes into --out (default model-out):
  assets/florence/vision_encoder.onnx   pixel_values [1,3,768,768] -> image_features [1,577,768]
  assets/florence/embed_tokens.onnx     input_ids -> inputs_embeds
  assets/florence/encoder_model.onnx    inputs_embeds + attention_mask -> last_hidden_state
  assets/florence/decoder_model.onnx    merged decoder (with/without past key values)
  assets/florence/florence_config.json  prompt token ids per task, special ids, image normalisation
  assets/florence/tokens.json           token string per id (byte-level BPE), for decoding
  fixtures/florence_pixels.bin          a test picture, preprocessed (float32 CHW, little-endian)
  fixtures/florence_fixtures.json       what this reference pipeline generates for it, per task
"""
import argparse
import json
import os
import shutil

import numpy as np
import onnxruntime as ort
from huggingface_hub import hf_hub_download
from PIL import Image, ImageDraw
from tokenizers import Tokenizer

REPO = "onnx-community/Florence-2-base"
FILES = {
    "vision_encoder.onnx": "onnx/vision_encoder_q4.onnx",
    "embed_tokens.onnx": "onnx/embed_tokens_int8.onnx",
    "encoder_model.onnx": "onnx/encoder_model_q4.onnx",
    "decoder_model.onnx": "onnx/decoder_model_merged_q4.onnx",
}
# Florence's task prompts (its processor turns "<CAPTION>" etc. into these sentences).
TASKS = {
    "caption": "What does the image describe?",
    "detailed": "Describe in detail what is shown in the image.",
    "objects": "Locate the objects with category name in the image.",
}
SIZE = 768
MEAN = [0.485, 0.456, 0.406]
STD = [0.229, 0.224, 0.225]
START = EOS = 2
MAX_TOKENS = 120


def test_picture():
    """A simple scene: sky, grass, a red ball and a tree."""
    img = Image.new("RGB", (SIZE, SIZE), (120, 180, 240))
    d = ImageDraw.Draw(img)
    d.rectangle([0, 500, SIZE, SIZE], fill=(60, 160, 60))
    d.ellipse([300, 420, 460, 580], fill=(220, 30, 30))
    d.rectangle([600, 300, 640, 520], fill=(110, 70, 30))
    d.ellipse([520, 160, 720, 360], fill=(30, 120, 40))
    return img


def preprocess(img):
    px = np.asarray(img.convert("RGB").resize((SIZE, SIZE), Image.BICUBIC), dtype=np.float32) / 255.0
    return ((px - MEAN) / STD).transpose(2, 0, 1).astype(np.float32)


class Reference:
    """The same greedy decoding the app does (Kotlin: FlorenceModel), for the parity fixture."""

    def __init__(self, folder):
        so = ort.SessionOptions()
        so.intra_op_num_threads = 4
        self.s = {k[:-5]: ort.InferenceSession(os.path.join(folder, k), so, providers=["CPUExecutionProvider"]) for k in FILES}
        dec = self.s["decoder_model"]
        self.dec_inputs = {i.name: i for i in dec.get_inputs()}
        self.dec_outputs = [o.name for o in dec.get_outputs()]

    def embed(self, ids):
        return self.s["embed_tokens"].run(None, {"input_ids": np.array([ids], dtype=np.int64)})[0]

    def generate(self, pixels, prompt_ids):
        feats = self.s["vision_encoder"].run(None, {"pixel_values": pixels[None]})[0]
        embeds = np.concatenate([feats, self.embed(prompt_ids)], axis=1).astype(np.float32)
        mask = np.ones(embeds.shape[:2], dtype=np.int64)
        hidden = self.s["encoder_model"].run(None, {"inputs_embeds": embeds, "attention_mask": mask})[0]
        past = {}
        for name, inp in self.dec_inputs.items():
            if name.startswith("past_key_values"):
                past[name] = np.zeros((1, inp.shape[1], 0, inp.shape[3]), dtype=np.float32)
        out, token = [], START
        for step in range(MAX_TOKENS):
            feeds = {"inputs_embeds": self.embed([token]).astype(np.float32), "encoder_hidden_states": hidden,
                     "encoder_attention_mask": mask, "use_cache_branch": np.array([step > 0])}
            feeds.update(past)
            outs = dict(zip(self.dec_outputs, self.s["decoder_model"].run(None, feeds)))
            logits = outs["logits"][0, -1].copy()
            seq = [START] + out
            for i in range(len(seq) - 2):  # never repeat a 3-token sequence
                if seq[i] == seq[-2] and seq[i + 1] == seq[-1]:
                    logits[seq[i + 2]] = -1e9
            token = int(np.argmax(logits))
            for name, value in outs.items():
                # The encoder's keys/values come from the first step only.
                if name.startswith("present") and (step == 0 or ".decoder." in name):
                    past[name.replace("present", "past_key_values")] = value
            if token == EOS:
                break
            out.append(token)
        return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="model-out")
    args = ap.parse_args()
    folder = os.path.join(args.out, "assets", "florence")
    fixtures = os.path.join(args.out, "fixtures")
    os.makedirs(folder, exist_ok=True)
    os.makedirs(fixtures, exist_ok=True)

    for name, remote in FILES.items():
        dst = os.path.join(folder, name)
        if os.path.exists(dst):
            os.remove(dst)  # the Hugging Face cache hands out read-only files
        shutil.copyfile(hf_hub_download(REPO, remote), dst)
        print(f"{name}: {os.path.getsize(os.path.join(folder, name)) / 1e6:.1f} MB")

    tok = Tokenizer.from_file(hf_hub_download(REPO, "tokenizer.json"))
    vocab = tok.get_vocab(with_added_tokens=True)
    tokens = [""] * (max(vocab.values()) + 1)
    for t, i in vocab.items():
        tokens[i] = t
    special = sorted(i for t, i in vocab.items() if t in ("<s>", "</s>", "<pad>", "<unk>", "<mask>"))
    with open(os.path.join(folder, "tokens.json"), "w") as f:
        json.dump(tokens, f, ensure_ascii=False, separators=(",", ":"))
    prompts = {task: tok.encode(text).ids for task, text in TASKS.items()}
    config = {
        "model": "Florence-2-base",
        "image_size": SIZE, "mean": MEAN, "std": STD,
        "decoder_start": START, "eos": EOS, "special": special,
        "max_tokens": MAX_TOKENS, "prompts": prompts,
    }
    with open(os.path.join(folder, "florence_config.json"), "w") as f:
        json.dump(config, f, indent=1)

    pixels = preprocess(test_picture())
    pixels.astype("<f4").tofile(os.path.join(fixtures, "florence_pixels.bin"))
    ref = Reference(folder)
    expected = {}
    for task, ids in prompts.items():
        out = ref.generate(pixels, ids)
        expected[task] = {"ids": out, "text": tok.decode(out, skip_special_tokens=False)}
        print(task, "->", expected[task]["text"])
    with open(os.path.join(fixtures, "florence_fixtures.json"), "w") as f:
        json.dump(expected, f, indent=1, ensure_ascii=False)


if __name__ == "__main__":
    main()
