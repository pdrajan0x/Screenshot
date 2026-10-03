#!/usr/bin/env bash
# Fetches the pinned llama.cpp release that core:llm builds against.
set -euo pipefail
TAG="${LLAMA_TAG:-b11371}"
DIR="$(cd "$(dirname "$0")/.." && pwd)/third_party/llama.cpp"
if [ -f "$DIR/.tag" ] && [ "$(cat "$DIR/.tag")" = "$TAG" ]; then
  echo "llama.cpp $TAG already present"; exit 0
fi
rm -rf "$DIR"
git clone --quiet --depth 1 --branch "$TAG" https://github.com/ggml-org/llama.cpp "$DIR"
echo "$TAG" > "$DIR/.tag"
echo "llama.cpp $TAG fetched into $DIR"
