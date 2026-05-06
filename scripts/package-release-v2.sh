#!/usr/bin/env bash
# Package the okapi-bridge plugin tarball for the host platform using
# jpackage (JDK 14+ tool). Replaces the earlier Go-shim + bundle-jre.sh
# pipeline (issue neokapi/neokapi#438 phase 8).
#
# Layout produced:
#
#   kapi-okapi-bridge_<okapi-version>_<os>_<arch>.tar.gz
#   └── okapi-bridge/
#       ├── manifest.json                    # v2 manifest, declares formats[] + daemon block
#       ├── (jpackage app-image structure with bundled JRE + JAR)
#       └── ...
#
# Inputs (flags):
#   --okapi-version <v>   Okapi Framework version (e.g. 1.47.0)
#   --os <linux|darwin|windows>
#   --arch <amd64|arm64>
#
# Requires:
#   - jpackage on PATH (JDK 17+ recommended; the bundled runtime image
#     matches the JDK that ran jpackage)
#   - The shaded JAR at
#     okapi-releases/<v>/target/neokapi-bridge-<v>-jar-with-dependencies.jar
#     (produced by `make build V=<v>`)
#   - Go on PATH for the manifest-gen helper

set -euo pipefail

OKAPI_VERSION=""
OS=""
ARCH=""

while [ $# -gt 0 ]; do
  case "$1" in
    --okapi-version) OKAPI_VERSION="$2"; shift 2 ;;
    --os) OS="$2"; shift 2 ;;
    --arch) ARCH="$2"; shift 2 ;;
    *) echo "$0: unknown flag: $1" >&2; exit 2 ;;
  esac
done

if [ -z "$OKAPI_VERSION" ] || [ -z "$OS" ] || [ -z "$ARCH" ]; then
  echo "Usage: $0 --okapi-version <v> --os <linux|darwin|windows> --arch <amd64|arm64>" >&2
  exit 2
fi

PLUGIN_NAME="okapi-bridge"
LAUNCHER="kapi-okapi-bridge"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAR_DIR="$ROOT/okapi-releases/${OKAPI_VERSION}/target"

# The shaded JAR is named after the bridge version (project.version),
# not the Okapi version. Glob to find it without hard-coding the bridge
# version into this script.
JAR_PATH=$(ls "$JAR_DIR"/neokapi-bridge-*-jar-with-dependencies.jar 2>/dev/null | grep -v '^original-' | head -1)
if [ -z "$JAR_PATH" ] || [ ! -f "$JAR_PATH" ]; then
  echo "$0: shaded JAR not found in $JAR_DIR" >&2
  echo "  Run \`make build V=$OKAPI_VERSION\` first." >&2
  exit 1
fi
JAR_NAME=$(basename "$JAR_PATH")

DIST="$ROOT/dist/v2"
WORK="$DIST/work-${OS}-${ARCH}-${OKAPI_VERSION}"
rm -rf "$WORK"
mkdir -p "$WORK" "$DIST"

# jpackage --input copies *every* file from the input dir into the
# app-image. Stage the shaded JAR in its own clean dir so we don't drag
# Maven's intermediate artifacts (original-*.jar, classes/, etc.) along.
JAR_STAGE="$WORK/jar"
mkdir -p "$JAR_STAGE"
cp "$JAR_PATH" "$JAR_STAGE/$JAR_NAME"

echo ">> jpackage app-image (okapi $OKAPI_VERSION, $OS/$ARCH)..."
# jpackage rejects --app-version values whose first component is 0
# ("The first number in an app-version cannot be zero or negative"),
# but we still want to release tarballs for old Okapi versions like
# 0.38. Remap 0.X to 1.0.X for jpackage's own metadata only — the
# manifest's plugin version (set later by manifest-gen) keeps the
# real Okapi version, which is what users see.
JPACKAGE_VERSION="$OKAPI_VERSION"
case "$JPACKAGE_VERSION" in
  0.*) JPACKAGE_VERSION="1.0.${JPACKAGE_VERSION#0.}" ;;
esac

# --type app-image emits a self-contained directory with native launcher,
# bundled JRE (built from the JDK running jpackage), and the JAR.
# jpackage requires the build to run on the target OS — cross-build is
# not supported; the release matrix runs one job per host.
jpackage \
  --type app-image \
  --name "$LAUNCHER" \
  --input "$JAR_STAGE" \
  --main-jar "$JAR_NAME" \
  --main-class neokapi.bridge.OkapiBridgeServer \
  --app-version "$JPACKAGE_VERSION" \
  --java-options '-Xss512k' \
  --java-options '-Djava.security.egd=file:/dev/./urandom' \
  --dest "$WORK"

# Reshape the app-image into the unified-plugin layout. The jpackage
# output dir varies by platform; we move it to "okapi-bridge/" and
# record the relative launcher path for the manifest.
case "$OS" in
  darwin)
    mv "$WORK/${LAUNCHER}.app" "$WORK/$PLUGIN_NAME"
    BIN_REL="Contents/MacOS/$LAUNCHER"
    ;;
  linux)
    mv "$WORK/$LAUNCHER" "$WORK/$PLUGIN_NAME"
    BIN_REL="bin/$LAUNCHER"
    ;;
  windows)
    mv "$WORK/$LAUNCHER" "$WORK/$PLUGIN_NAME"
    BIN_REL="${LAUNCHER}.exe"
    ;;
  *)
    echo "$0: unknown OS: $OS" >&2; exit 2 ;;
esac

# Probe the freshly built launcher for filter metadata. The launcher
# runs under its own bundled JRE so this also smoke-tests the bundle.
echo ">> introspecting filters..."
LIST_FILTERS_JSON="$WORK/filters.json"
"$WORK/$PLUGIN_NAME/$BIN_REL" --list-filters > "$LIST_FILTERS_JSON"

# Build manifest-gen the first time.
if [ ! -x "$WORK/manifest-gen" ]; then
  (cd "$ROOT/cmd/manifest-gen" && go build -o "$WORK/manifest-gen" .)
fi

echo ">> generating v2 manifest.json..."
"$WORK/manifest-gen" \
  --okapi-version "$OKAPI_VERSION" \
  --filters-json "$LIST_FILTERS_JSON" \
  --plugin "$PLUGIN_NAME" \
  --binary "$BIN_REL" \
  --output "$WORK/$PLUGIN_NAME/manifest.json"

# Tarball the okapi-bridge/ dir + sha256 sidecar.
TARBALL="$DIST/kapi-okapi-bridge_${OKAPI_VERSION}_${OS}_${ARCH}.tar.gz"
rm -f "$TARBALL" "${TARBALL}.sha256"
tar -czf "$TARBALL" -C "$WORK" "$PLUGIN_NAME"

if command -v sha256sum >/dev/null 2>&1; then
  sha256sum "$TARBALL" | awk '{print $1}' > "${TARBALL}.sha256"
else
  shasum -a 256 "$TARBALL" | awk '{print $1}' > "${TARBALL}.sha256"
fi

echo ">> packaged $(basename "$TARBALL")"
echo "  size:   $(du -h "$TARBALL" | awk '{print $1}')"
echo "  sha256: $(cat "${TARBALL}.sha256")"
