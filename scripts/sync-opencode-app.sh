#!/usr/bin/env bash
# Rebuild and vendor OpenCode @opencode-ai/app production assets into the Android APK.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
REF="${OPENCODE_REF:-v1.18.31}"
SRC="${OPENCODE_SRC:-}"
WORKDIR="${TMPDIR:-/tmp}/opencode-app-src"
DEST="$ROOT/app/src/main/assets/opencode-app"
PIN="$ROOT/frontend/OPENCODE_APP_VERSION"
PATCH_DIR="$ROOT/frontend/patches"

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

apply_android_embed_patch() {
  if [[ ! -d "$PATCH_DIR" ]]; then
    echo "missing $PATCH_DIR" >&2
    exit 1
  fi
  local patch base
  for patch in "$PATCH_DIR"/*.patch; do
    [[ -f "$patch" ]] || continue
    base="$(basename "$patch")"
    if [[ "$base" == *no-origin* ]] && grep -q AndroidMemoryRouter "$SRC/packages/app/src/entry.tsx"; then
      echo "Already applied $base"
      continue
    fi
    if [[ "$base" == *disconnected* ]] && grep -q DISCONNECTED_HTTP_SERVER "$SRC/packages/app/src/context/server.tsx"; then
      echo "Already applied $base"
      continue
    fi
    if [[ "$base" == *portrait-settings* ]] && grep -q "max-width: 839px" "$SRC/packages/app/src/components/settings-v2/settings-v2.css"; then
      echo "Already applied $base"
      continue
    fi
    if [[ "$base" == *settings-accordion* ]] && grep -q "SettingsSectionV2" "$SRC/packages/app/src/components/settings-v2/general.tsx"; then
      echo "Already applied $base"
      continue
    fi
    if [[ "$base" == *touchable-home* ]] && grep -q 'server.key || "offline"' "$SRC/packages/app/src/app.tsx"; then
      echo "Already applied $base"
      continue
    fi
    if [[ "$base" == *ui-home-empty* ]] && grep -q 'data-slot="home-sessions-body"' "$SRC/packages/app/src/pages/home/home-sessions-view.tsx"; then
      echo "Already applied $base"
      continue
    fi
    if git -C "$SRC" apply --check "$patch" >/dev/null 2>&1; then
      git -C "$SRC" apply "$patch"
      echo "Applied $patch"
      continue
    fi
    echo "Failed to apply $patch against $REF" >&2
    exit 1
  done
}

apply_android_embed_patch

COMMIT="$(git -C "$SRC" rev-parse HEAD)"
cd "$SRC"
bun install --filter @opencode-ai/app
(
  cd packages/app
  OPENCODE_CHANNEL=prod bunx vite build --base ./ --sourcemap false
)

rm -rf "$DEST"
mkdir -p "$DEST"
cp -a "$SRC/packages/app/dist/." "$DEST/"
rm -f "$DEST/_headers"
find "$DEST" -name '*.map' -delete

mkdir -p "$(dirname "$PIN")"
cat > "$PIN" <<EOF
name=@opencode-ai/app
tag=$REF
commit=$COMMIT
source=https://github.com/anomalyco/opencode
built_with=OPENCODE_CHANNEL=prod vite build --base ./ --sourcemap false
android_patch=frontend/patches/*.patch
android_patch_purpose=Do not treat the WebView asset origin as a builtin HTTP server. Provide an offline SDK stub (no fetch to 127.0.0.1:0) so Layout/ServerSDK can init with zero configured servers. Disable touch-eating tooltips on coarse pointers. Hide DEV channel chrome. Empty home opens Settings. Servers are added via Settings → Server.
EOF

echo "Vendored $REF ($COMMIT) into $DEST"
du -sh "$DEST"
