"""Writes reference token ids from OpenCLIP's SimpleTokenizer for the Kotlin parity test.

Usage: python gen_tokenizer_fixtures.py OUT.json [path/to/open_clip/tokenizer.py]

Works without torch: when `open_clip` can't be imported, pass the path to a copy of
open_clip's tokenizer.py (e.g. from the unzipped open_clip_torch wheel).
"""
import importlib.util
import json
import sys
import types

TEXTS = [
    "car",
    "a photo of a dog",
    "Movie ticket for Interstellar",
    "₹499 paid to Swiggy",
    "UPI ID: rahul.k@okhdfcbank",
    "नमस्ते दुनिया",
    "मेरा नाम राहुल है और मैं दिल्ली में रहता हूँ",
    "Hello, World!!! 123",
    "it's a cat's toy, isn't it? We'll see; they'd've",
    "HTTPS://Example.COM/path?q=1&x=2",
    "  multiple   spaces\tand\nnewlines  ",
    "“Smart quotes” and ‘single’",
    "ﬁnance ﬂow",
    "ＦＵＬＬＷＩＤＴＨ text",
    "emoji 😀🚗🍕",
    "&amp; &lt;tag&gt; &#39;x&#39; &#x263A;",
    "Ünïcödé café naïve façade",
    "<start_of_text> literal markers <end_of_text>",
    "mixedCASE CamelCase snake_case kebab-case",
    "12:30 PM, 03/10/2026, +91 98765 43210",
    "the quick brown fox jumps over the lazy dog " * 8,
    "你好世界",
    "مرحبا بالعالم",
    "வணக்கம்",
    "Order #A1B2C3 — Delivered by Amazon on Tue, 29 Sep",
    "OTP is 482913. Do not share it with anyone.",
    "screenshot of a youtube video",
    "",
]


def load_tokenizer(tokenizer_py=None):
    try:
        import open_clip  # noqa: F401
        from open_clip.tokenizer import SimpleTokenizer
        return SimpleTokenizer()
    except Exception:
        if not tokenizer_py:
            raise
    torch_stub = types.ModuleType("torch")
    torch_stub.LongTensor = object
    torch_stub.Tensor = object
    sys.modules.setdefault("torch", torch_stub)
    np_stub = sys.modules.get("numpy") or types.ModuleType("numpy")
    sys.modules.setdefault("numpy", np_stub)
    spec = importlib.util.spec_from_file_location("oc_tokenizer", tokenizer_py)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod.SimpleTokenizer()


def tokenize(tok, text, context_length=77):
    tokens = [tok.sot_token_id] + tok.encode(text) + [tok.eot_token_id]
    if len(tokens) > context_length:
        tokens = tokens[:context_length]
        tokens[-1] = tok.eot_token_id
    return tokens + [0] * (context_length - len(tokens))


def main():
    out = sys.argv[1]
    tok = load_tokenizer(sys.argv[2] if len(sys.argv) > 2 else None)
    fixtures = [{"text": t, "ids": tokenize(tok, t)} for t in TEXTS]
    with open(out, "w", encoding="utf-8") as f:
        json.dump({"sot": tok.sot_token_id, "eot": tok.eot_token_id, "cases": fixtures}, f, ensure_ascii=False, indent=1)
    print(f"wrote {len(fixtures)} cases to {out}")


if __name__ == "__main__":
    main()
