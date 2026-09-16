#!/usr/bin/env bash
# Rebuild and vendor OpenCode @opencode-ai/app production assets into the Android APK.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
REF="${OPENCODE_REF:-v1.18.31}"
SRC="${OPENCODE_SRC:-}"
WORKDIR="${TMPDIR:-/tmp}/opencode-app-src"
DEST="$ROOT/app/src/main/assets/opencode-app"
PIN="$ROOT/frontend/OPENCODE_APP_VERSION"
BOOTSTRAP="$ROOT/frontend/bootstrap.html"

if ! command -v bun >/dev/null 2>&1; then
  echo "bun is required. Install from https://bun.sh" >&2
  exit 1
fi

cleanup() {
  if [[ -z "${SRC_PROVIDED:-}" && -d "$WORKDIR" ]]; then
    rm -rf "$WORKDIR"
  fi
}

SRC_PROVIDED=""
if [[ -n "$SRC" ]]; then
  SRC_PROVIDED=1
fi
trap cleanup EXIT

if [[ -z "$SRC" ]]; then
  rm -rf "$WORKDIR"
  git clone --depth 1 --branch "$REF" https://github.com/anomalyco/opencode.git "$WORKDIR"
  SRC="$WORKDIR"
fi

COMMIT="$(git -C "$SRC" rev-parse HEAD)"
cd "$SRC"
bun install --filter @opencode-ai/app
(
  cd packages/app
  bunx vite build --base ./ --sourcemap false
)

rm -rf "$DEST"
mkdir -p "$DEST"
cp -a "$SRC/packages/app/dist/." "$DEST/"
rm -f "$DEST/_headers"
find "$DEST" -name '*.map' -delete
cp "$BOOTSTRAP" "$DEST/bootstrap.html"

mkdir -p "$(dirname "$PIN")"
cat > "$PIN" <<EOF
name=@opencode-ai/app
tag=$REF
commit=$COMMIT
source=https://github.com/anomalyco/opencode
built_with=vite build --base ./ --sourcemap false
EOF

echo "Vendored $REF ($COMMIT) into $DEST"
du -sh "$DEST"
