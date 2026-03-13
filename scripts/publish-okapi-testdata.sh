#!/usr/bin/env bash
#
# publish-okapi-testdata.sh — Publish Okapi test resources as a tarball.
#
# Usage:
#   ./scripts/publish-okapi-testdata.sh [OKAPI_VERSION]
#
# Clones the Okapi Framework from GitLab at the specified version tag using
# sparse checkout, tars the original file hierarchy as-is, and uploads it as
# a GitHub release asset.
#
# Arguments:
#   OKAPI_VERSION  — Okapi Framework version (default: 1.48.0). Used as the
#                    GitLab tag (v1.48.0) and the GitHub release tag
#                    (okapi-testdata-{version}).
#
# Environment:
#   GITHUB_TOKEN   — GitHub token for creating/uploading the release.
#                    Falls back to `gh auth token` if unset.
#   SKIP_PUBLISH   — If set (e.g. SKIP_PUBLISH=1), build the tarball but don't
#                    upload to GitHub. Useful for local testing.

set -euo pipefail

OKAPI_VERSION="${1:-1.48.0}"
GITLAB_REPO="https://gitlab.com/okapiframework/okapi.git"
GITLAB_TAG="v${OKAPI_VERSION}"
GITHUB_REPO="neokapi/okapi-bridge"
RELEASE_TAG="okapi-testdata-${OKAPI_VERSION}"
ASSET_NAME="okapi-testdata.tar.gz"

# Resolve token.
GITHUB_TOKEN="${GITHUB_TOKEN:-$(gh auth token 2>/dev/null || true)}"
if [ -z "$GITHUB_TOKEN" ] && [ "${SKIP_PUBLISH:-}" = "" ]; then
    echo "ERROR: No GitHub token. Set GITHUB_TOKEN or log in with gh auth login." >&2
    exit 1
fi

# Work in a temp directory.
WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT

echo "=== Cloning Okapi Framework @ ${GITLAB_TAG} (sparse checkout) ==="

cd "$WORK_DIR"

# Sparse checkout — only test resource directories.
git clone --no-checkout --depth=1 --branch "${GITLAB_TAG}" "${GITLAB_REPO}" okapi --quiet
cd okapi
git sparse-checkout init --cone
git sparse-checkout set \
    okapi/filters/html/src/test/resources \
    okapi/filters/json/src/test/resources \
    okapi/filters/yaml/src/test/resources \
    okapi/filters/xmlstream/src/test/resources \
    okapi/filters/xliff/src/test/resources \
    okapi/filters/xliff2/src/test/resources \
    okapi/filters/properties/src/test/resources \
    okapi/filters/po/src/test/resources \
    okapi/filters/plaintext/src/test/resources \
    okapi/filters/markdown/src/test/resources \
    okapi/filters/its/src/test/resources \
    okapi/filters/dtd/src/test/resources \
    okapi/filters/tmx/src/test/resources \
    okapi/filters/ts/src/test/resources \
    okapi/filters/regex/src/test/resources \
    okapi/filters/doxygen/src/test/resources \
    okapi/filters/tex/src/test/resources \
    okapi/filters/wiki/src/test/resources \
    okapi/filters/mosestext/src/test/resources \
    okapi/filters/subtitles/src/test/resources \
    okapi/filters/php/src/test/resources \
    okapi/filters/transtable/src/test/resources \
    okapi/filters/openxml/src/test/resources \
    okapi/filters/idml/src/test/resources \
    okapi/filters/icml/src/test/resources \
    okapi/filters/openoffice/src/test/resources \
    okapi/filters/mif/src/test/resources \
    okapi/filters/rtf/src/test/resources \
    okapi/filters/epub/src/test/resources \
    okapi/filters/archive/src/test/resources \
    okapi/filters/pdf/src/test/resources \
    okapi/filters/ttx/src/test/resources \
    okapi/filters/txml/src/test/resources \
    okapi/filters/table/src/test/resources \
    okapi/filters/vignette/src/test/resources \
    okapi/filters/xini/src/test/resources \
    okapi/filters/autoxliff/src/test/resources \
    okapi/filters/multiparsers/src/test/resources \
    okapi/filters/sdlpackage/src/test/resources \
    okapi/filters/wsxzpackage/src/test/resources \
    integration-tests/okapi/src/test/resources
git checkout --quiet

echo "  Clone complete."

# ---------------------------------------------------------------------------
# Package the tarball — preserving the original Okapi file hierarchy.
# ---------------------------------------------------------------------------

echo ""
echo "=== Packaging tarball ==="

cd "$WORK_DIR"

# Build a file list of what to include (test resource dirs that exist).
INCLUDE_PATHS=()
for dir in okapi/okapi/filters/*/src/test/resources; do
    [ -d "$dir" ] && INCLUDE_PATHS+=("$dir")
done
if [ -d "okapi/integration-tests/okapi/src/test/resources" ]; then
    INCLUDE_PATHS+=("okapi/integration-tests/okapi/src/test/resources")
fi

tar czf "$ASSET_NAME" -C okapi \
    $(printf '%s\n' "${INCLUDE_PATHS[@]}" | sed 's|^okapi/||')

SIZE=$(du -sh "$ASSET_NAME" | cut -f1)
echo "  $ASSET_NAME: $SIZE"

# ---------------------------------------------------------------------------
# Publish to GitHub release.
# ---------------------------------------------------------------------------

if [ "${SKIP_PUBLISH:-}" != "" ]; then
    echo ""
    echo "=== SKIP_PUBLISH set — not uploading to GitHub ==="
    echo "Tarball available at: $WORK_DIR/$ASSET_NAME"
    exit 0
fi

echo ""
echo "=== Publishing to GitHub release: $RELEASE_TAG ==="

# Delete existing release if present (to replace the asset).
if gh release view "$RELEASE_TAG" --repo "$GITHUB_REPO" &>/dev/null; then
    echo "  Deleting existing release $RELEASE_TAG..."
    gh release delete "$RELEASE_TAG" --repo "$GITHUB_REPO" --yes --cleanup-tag
fi

RELEASE_BODY="Test resources from Okapi Framework v${OKAPI_VERSION} (https://gitlab.com/okapiframework/okapi/-/tree/v${OKAPI_VERSION}).

Contains filter test resources in their original Okapi directory hierarchy.

- Generated: $(date -u +"%Y-%m-%dT%H:%M:%SZ")
- Script: scripts/publish-okapi-testdata.sh"

gh release create "$RELEASE_TAG" \
    --repo "$GITHUB_REPO" \
    --title "Okapi Test Data v${OKAPI_VERSION}" \
    --notes "$RELEASE_BODY" \
    "$WORK_DIR/$ASSET_NAME"

echo ""
echo "Done. Release: https://github.com/$GITHUB_REPO/releases/tag/$RELEASE_TAG"
