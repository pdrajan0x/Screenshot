"""Compares MobileCLIP2 sizes on what Dot Screenshots asks of them: precise picture keywords.

For each model: photo embeddings (the app's square crops), the keyword prompts from
core/ml/src/main/assets/clip/picture_words.json, and the app's keyword rule (PictureTagger) over a
small grid of thresholds. Reports, per model, the most photos keyworded right while at most N get a
wrong look-alike keyword (a bike called a car), plus CPU time per photo and size.

Photos: ImageNet sample images (github.com/EliSchwartz/imagenet-sample-images), labels in eval/photos.json.
Run in CI (needs Hugging Face for the weights): python tools/model/compare_clip.py
"""
import copy, json, math, os, time, urllib.request

import numpy as np
import open_clip
import torch
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
WORDS = json.load(open(os.path.join(HERE, "../../core/ml/src/main/assets/clip/picture_words.json")))
PHOTOS = json.load(open(os.path.join(HERE, "eval/photos.json")))
MODELS = [("MobileCLIP2-S0", "dfndr2b"), ("MobileCLIP2-S2", "dfndr2b")]
BASE = "https://raw.githubusercontent.com/EliSchwartz/imagenet-sample-images/master/"


def fetch(cache):
    os.makedirs(cache, exist_ok=True)
    for name in PHOTOS:
        path = os.path.join(cache, name)
        if not os.path.exists(path):
            urllib.request.urlretrieve(BASE + name, path)
    return {name: Image.open(os.path.join(cache, name)).convert("RGB") for name in PHOTOS}


def crops(img, size, maxc=2):
    """The app's CropPlanner: one square crop, or several along the long side."""
    w, h = img.size
    side, long = min(w, h), max(w, h)
    n = 1 if long / side <= 1.35 else min(maxc, max(2, math.ceil(long / side - 0.2)))
    offs = [(long - side) // 2] if n == 1 else [round(i * (long - side) / (n - 1)) for i in range(n)]
    out = []
    for o in offs:
        box = (0, o, side, o + side) if h >= w else (o, 0, o + side, side)
        out.append(np.asarray(img.crop(box).resize((size, size), Image.BILINEAR), dtype=np.float32).transpose(2, 0, 1) / 255.0)
    return np.stack(out)


def reparam(model):
    try:
        from timm.utils.model import reparameterize_model
    except ImportError:
        from timm.utils import reparameterize_model
    return reparameterize_model(model)


def keywords(E, P, owner, B, groups, scale, lead, share, multi_lead=0.05, more=0.045, skip=()):
    """Same rule as core/engine PictureTagger.keywords."""
    S = E @ P.T
    nwords = len(groups)
    sw = np.full(nwords, -1.0)
    for j, o in enumerate(owner):
        sw[o] = max(sw[o], S[:, j].max())
    base = (E @ B.T).max()
    picked, i = [], 0
    for g in WORDS["groups"]:
        idx = list(range(i, i + len(g["words"])))
        i += len(g["words"])
        s = sw[idx]
        if g["id"] in skip:
            continue
        if g.get("multi"):
            picked += [(k, s[n] - base) for n, k in enumerate(idx) if s[n] - base >= multi_lead]
            continue
        t = int(np.argmax(s))
        p = 1.0 / np.exp((s - s[t]) * scale).sum()
        if p >= share and s[t] - base >= lead:
            picked.append((idx[t], s[t] - base))
    picked.sort(key=lambda x: -x[1])
    return [k for n, (k, d) in enumerate(picked) if n == 0 or d >= more][:5]


def main():
    imgs = fetch(os.path.join(HERE, "eval/.photos"))
    words, groups, prompts, owner = [], [], [], []
    for g in WORDS["groups"]:
        for w in g["words"]:
            words.append(w["word"]); groups.append(g["id"])
            for p in w["prompts"]:
                prompts.append(p); owner.append(len(words) - 1)
    group_of = dict(zip(words, groups))
    torch.set_num_threads(2)
    torch.backends.mha.set_fastpath_enabled(False)
    report = []
    for name, pretrained in MODELS:
        model, _, _ = open_clip.create_model_and_transforms(name, pretrained=pretrained)
        model = reparam(copy.deepcopy(model)).eval()
        tok = open_clip.get_tokenizer(name)
        pp = model.visual.preprocess_cfg
        size = pp["size"][0] if isinstance(pp["size"], (list, tuple)) else pp["size"]
        mean = torch.tensor(pp["mean"]).view(1, 3, 1, 1); std = torch.tensor(pp["std"]).view(1, 3, 1, 1)
        scale = float(model.logit_scale.exp())
        n_img = sum(p.numel() for p in model.visual.parameters()) / 1e6
        n_txt = sum(p.numel() for n, p in model.named_parameters() if not n.startswith("visual.")) / 1e6
        with torch.no_grad():
            norm = lambda x: x / x.norm(dim=-1, keepdim=True)
            P = norm(model.encode_text(tok(prompts))).numpy()
            B = norm(model.encode_text(tok(WORDS["background"]))).numpy()
            E, times = {}, []
            for k, img in imgs.items():
                x = (torch.from_numpy(crops(img, size)) - mean) / std
                t0 = time.perf_counter()
                E[k] = norm(model.encode_image(x)).numpy()
                times.append((time.perf_counter() - t0) / x.shape[0])
        best = {}
        for lead in (0.01, 0.02, 0.03, 0.04, 0.05):
            for share in (0.5, 0.6, 0.7, 0.75, 0.8, 0.9):
                for more in (0.035, 0.045, 0.06):
                    hit = wrong = 0
                    for k, allowed in PHOTOS.items():
                        got = [words[i] for i in keywords(E[k], P, owner, B, groups, scale, lead, share, more=more)]
                        hit += allowed[0] in got or any(a in got for a in allowed[1:])
                        g = group_of.get(allowed[0])
                        wrong += any(group_of[w] == g and w not in allowed for w in got)
                    for cap in (1, 2, 4):
                        if wrong <= cap and hit > best.get(cap, (0,))[0]:
                            best[cap] = (hit, wrong, lead, share, more)
        # Thresholds near the best, to pick robust ones (not just the single best cell).
        for lead in (0.0, 0.005, 0.01, 0.015, 0.02):
            row = []
            for share in (0.6, 0.65, 0.7, 0.75, 0.8):
                hit = wrong = 0
                for k, allowed in PHOTOS.items():
                    got = [words[i] for i in keywords(E[k], P, owner, B, groups, scale, lead, share, more=0.035)]
                    hit += any(a in got for a in allowed)
                    g = group_of.get(allowed[0])
                    wrong += any(group_of[w] == g and w not in allowed for w in got)
                row.append(f"{share}:{hit}/{wrong}")
            print(f"  grid lead {lead}: " + "  ".join(row), flush=True)
        # Screenshots of app screens should get no keywords (screenshot background prompts, text/screens groups skipped).
        with torch.no_grad():
            BS = norm(model.encode_text(tok(WORDS["background"] + WORDS["screenshot_background"]))).numpy()
            for f in sorted(os.listdir(os.path.join(HERE, "eval/screens"))):
                img = Image.open(os.path.join(HERE, "eval/screens", f)).convert("RGB")
                Es = norm(model.encode_image((torch.from_numpy(crops(img, size, 3)) - mean) / std)).numpy()
                for lead in (0.0, 0.01, 0.02):
                    got = [words[i] for i in keywords(Es, P, owner, BS, groups, scale, lead, 0.7, more=0.035, skip=("text", "screens"))]
                    print(f"  screen {f} lead {lead}: {got}", flush=True)
            # Look-alike search scale: how close a photo is to the query naming it vs the best other query.
            Q = norm(model.encode_text(tok([f"a photo of a {w}" for w in words]))).numpy()
            right, other = [], []
            for k, allowed in PHOTOS.items():
                sims = (E[k] @ Q.T).max(axis=0)
                i = words.index(allowed[0])
                right.append(sims[i]); other.append(np.delete(sims, i).max())
            print(f"  search: photo vs its own query median {np.median(right):.3f} (p10 {np.percentile(right, 10):.3f}); "
                  f"vs best other query median {np.median(other):.3f} (p90 {np.percentile(other, 90):.3f})", flush=True)
        ms = 1000 * float(np.median(times[1:]))
        line = f"{name}: params {n_img:.1f}M image + {n_txt:.1f}M text, {size}px, {ms:.0f} ms per crop (CI CPU, 2 threads)"
        for cap in (1, 2, 4):
            if cap in best:
                h, w, lead, share, more = best[cap]
                line += f"\n    at most {cap} wrong: {h}/{len(PHOTOS)} right, {w} wrong (lead {lead}, share {share}, more {more})"
        print(line, flush=True)
        report.append(line)
        del model
    with open(os.path.join(HERE, "eval/report.txt"), "w") as f:
        f.write("\n".join(report) + "\n")


if __name__ == "__main__":
    main()
