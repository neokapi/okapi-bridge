#!/usr/bin/env bash
#
# publish-okapi-testdata.sh — Build and publish okapi-testdata from the Okapi GitLab repo.
#
# Usage:
#   ./scripts/publish-okapi-testdata.sh [OKAPI_VERSION]
#
# Clones the Okapi Framework from GitLab at the specified version tag, extracts
# test resource files for all filters used by the bridge, runs Maven tests to
# generate Surefire/Failsafe XML reports, packages everything into a tarball,
# and uploads it as a GitHub release asset.
#
# Arguments:
#   OKAPI_VERSION  — Okapi Framework version (default: 1.48.0). Used as the
#                    GitLab tag (v1.48.0) and the GitHub release tag
#                    (okapi-testdata-1.48.0).
#
# Environment:
#   GITHUB_TOKEN   — GitHub token for creating/uploading the release.
#                    Falls back to `gh auth token` if unset.
#   SKIP_PUBLISH   — If set (e.g. SKIP_PUBLISH=1), build the tarball but don't
#                    upload to GitHub. Useful for local testing.
#   SKIP_SUREFIRE  — If set (e.g. SKIP_SUREFIRE=1), skip running Maven tests
#                    and omit surefire reports from the tarball.

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

# Find repo root.
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$SCRIPT_DIR/.."

# Work in a temp directory.
WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT

echo "=== Cloning Okapi Framework @ ${GITLAB_TAG} ==="

cd "$WORK_DIR"

if [ "${SKIP_SUREFIRE:-}" != "" ]; then
    # Sparse checkout: only fetch test resources (faster, no Maven needed).
    git init okapi --quiet
    cd okapi
    git remote add origin "$GITLAB_REPO"

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
        okapi/filters/regex/src/test/resources \
        okapi/filters/doxygen/src/test/resources \
        okapi/filters/dtd/src/test/resources \
        okapi/filters/tmx/src/test/resources \
        okapi/filters/ts/src/test/resources \
        okapi/filters/tex/src/test/resources \
        okapi/filters/wiki/src/test/resources \
        okapi/filters/mosestext/src/test/resources \
        okapi/filters/transtable/src/test/resources \
        okapi/filters/php/src/test/resources \
        okapi/filters/vignette/src/test/resources \
        okapi/filters/xini/src/test/resources \
        okapi/filters/autoxliff/src/test/resources \
        okapi/filters/multiparsers/src/test/resources \
        okapi/filters/sdlpackage/src/test/resources \
        okapi/filters/wsxzpackage/src/test/resources \
        okapi/filters/subtitles/src/test/resources \
        integration-tests/okapi/src/test/resources

    echo "  Fetching ${GITLAB_TAG} (sparse)..."
    git fetch --depth=1 origin "refs/tags/${GITLAB_TAG}" --quiet
    git checkout FETCH_HEAD --quiet
else
    # Full shallow clone: needed for Maven test execution.
    echo "  Fetching ${GITLAB_TAG} (full shallow clone for surefire)..."
    git clone --depth=1 --branch "${GITLAB_TAG}" "${GITLAB_REPO}" okapi --quiet
    cd okapi
fi

echo "  Clone complete."

# ---------------------------------------------------------------------------
# Build the testdata directory tree.
#
# Layout: okapi-testdata/okf_{id}/
#
# Each filter maps to one or more source directories in the Okapi repo.
# We copy unit test resources and integration test resources into a flat
# per-filter directory.
# ---------------------------------------------------------------------------

echo ""
echo "=== Building testdata directory ==="

OUT="$WORK_DIR/okapi-testdata"
mkdir -p "$OUT"

OKAPI="$WORK_DIR/okapi"
UNIT="$OKAPI/okapi/filters"
IT="$OKAPI/integration-tests/okapi/src/test/resources"

# copy_resources <target_dir> <source_dir> [<source_dir>...]
#   Copies all files from source dirs into the target. Creates target if needed.
#   Silently skips missing source dirs.
copy_resources() {
    local target="$1"
    shift
    local found=0
    for src in "$@"; do
        if [ -d "$src" ]; then
            mkdir -p "$target"
            # Use rsync to merge, preserving directory structure within source.
            rsync -a "$src/" "$target/"
            found=1
        fi
    done
    if [ "$found" -eq 0 ]; then
        echo "  SKIP $(basename "$target") — no source directories found"
        return
    fi
    local count
    count=$(find "$target" -type f | wc -l | tr -d ' ')
    echo "  $(basename "$target"): $count files"
}

# --- Phase 1: High-value text formats ---
copy_resources "$OUT/okf_html" \
    "$UNIT/html/src/test/resources" \
    "$IT/html"

copy_resources "$OUT/okf_json" \
    "$UNIT/json/src/test/resources" \
    "$IT/json"

copy_resources "$OUT/okf_yaml" \
    "$UNIT/yaml/src/test/resources/yaml" \
    "$IT/yaml"

copy_resources "$OUT/okf_xmlstream" \
    "$UNIT/xmlstream/src/test/resources" \
    "$IT/xmlstream"

copy_resources "$OUT/okf_xliff" \
    "$UNIT/xliff/src/test/resources" \
    "$IT/xliff"

copy_resources "$OUT/okf_xliff2" \
    "$UNIT/xliff2/src/test/resources" \
    "$IT/xliff2"

copy_resources "$OUT/okf_properties" \
    "$UNIT/properties/src/test/resources" \
    "$IT/properties"

copy_resources "$OUT/okf_po" \
    "$UNIT/po/src/test/resources" \
    "$IT/po"

copy_resources "$OUT/okf_plaintext" \
    "$UNIT/plaintext/src/test/resources" \
    "$IT/plaintext"

copy_resources "$OUT/okf_markdown" \
    "$UNIT/markdown/src/test/resources" \
    "$IT/markdown"

# --- Phase 2: Remaining text formats ---
copy_resources "$OUT/okf_html5" \
    "$UNIT/its/src/test/resources" \
    "$IT/htmlIts"

copy_resources "$OUT/okf_xml" \
    "$UNIT/its/src/test/resources" \
    "$IT/xml"

copy_resources "$OUT/okf_dtd" \
    "$UNIT/dtd/src/test/resources" \
    "$IT/dtd"

copy_resources "$OUT/okf_tmx" \
    "$UNIT/tmx/src/test/resources" \
    "$IT/tmx"

copy_resources "$OUT/okf_ts" \
    "$UNIT/ts/src/test/resources" \
    "$IT/ts"

copy_resources "$OUT/okf_regex" \
    "$UNIT/regex/src/test/resources" \
    "$IT/regex"

copy_resources "$OUT/okf_doxygen" \
    "$UNIT/doxygen/src/test/resources" \
    "$IT/doxygen"

copy_resources "$OUT/okf_tex" \
    "$UNIT/tex/src/test/resources" \
    "$IT/tex"

copy_resources "$OUT/okf_wiki" \
    "$UNIT/wiki/src/test/resources" \
    "$IT/wikitext"

copy_resources "$OUT/okf_mosestext" \
    "$UNIT/mosestext/src/test/resources"

copy_resources "$OUT/okf_vtt" \
    "$UNIT/subtitles/src/test/resources" \
    "$IT/vtt"

copy_resources "$OUT/okf_ttml" \
    "$UNIT/subtitles/src/test/resources" \
    "$IT/ttml"

copy_resources "$OUT/okf_phpcontent" \
    "$UNIT/php/src/test/resources"

copy_resources "$OUT/okf_messageformat" \
    "$IT/messageformat"

copy_resources "$OUT/okf_transtable" \
    "$UNIT/transtable/src/test/resources" \
    "$IT/transtable"

# --- Phase 3: Binary/container formats ---
copy_resources "$OUT/okf_openxml" \
    "$UNIT/openxml/src/test/resources" \
    "$IT/openxml"

copy_resources "$OUT/okf_idml" \
    "$UNIT/idml/src/test/resources" \
    "$IT/idml"

copy_resources "$OUT/okf_icml" \
    "$UNIT/icml/src/test/resources" \
    "$IT/icml"

copy_resources "$OUT/okf_openoffice" \
    "$UNIT/openoffice/src/test/resources" \
    "$IT/openoffice"

copy_resources "$OUT/okf_mif" \
    "$UNIT/mif/src/test/resources" \
    "$IT/mif"

copy_resources "$OUT/okf_rtf" \
    "$UNIT/rtf/src/test/resources"

copy_resources "$OUT/okf_epub" \
    "$UNIT/epub/src/test/resources"

copy_resources "$OUT/okf_archive" \
    "$UNIT/archive/src/test/resources" \
    "$IT/archive"

copy_resources "$OUT/okf_pdf" \
    "$UNIT/pdf/src/test/resources"

copy_resources "$OUT/okf_ttx" \
    "$UNIT/ttx/src/test/resources" \
    "$IT/ttx"

copy_resources "$OUT/okf_txml" \
    "$UNIT/txml/src/test/resources" \
    "$IT/txml"

# --- Phase 4: Specialized/table/plaintext variants ---
copy_resources "$OUT/okf_table" \
    "$UNIT/table/src/test/resources" \
    "$IT/table"

copy_resources "$OUT/okf_vignette" \
    "$UNIT/vignette/src/test/resources"

copy_resources "$OUT/okf_xini" \
    "$UNIT/xini/src/test/resources" \
    "$IT/xini"

# --- Phase 5: Package/bundle filters ---
copy_resources "$OUT/okf_autoxliff" \
    "$UNIT/autoxliff/src/test/resources"

copy_resources "$OUT/okf_multiparsers" \
    "$UNIT/multiparsers/src/test/resources"

copy_resources "$OUT/okf_sdlpackage" \
    "$UNIT/sdlpackage/src/test/resources"

copy_resources "$OUT/okf_wsxzpackage" \
    "$UNIT/wsxzpackage/src/test/resources"

# ---------------------------------------------------------------------------
# Phase 6: Generate Surefire/Failsafe XML reports.
#
# Run Maven verify on the filters module to produce test reports. These are
# included in the tarball under surefire/{filter}/TEST-*.xml so neokapi can
# use them for test comparison without a separate publish step.
# ---------------------------------------------------------------------------

if [ "${SKIP_SUREFIRE:-}" = "" ]; then
    echo ""
    echo "=== Running Maven verify to generate Surefire reports ==="
    echo "  This may take several minutes..."

    cd "$OKAPI"

    # Run verify (not just test) to include Failsafe integration tests (*IT.java).
    # -T4 runs 4 threads for faster builds.
    # Failure ignore ensures we get reports even when tests fail.
    mvn verify \
        -pl okapi/filters \
        -am \
        -T4 \
        -Dmaven.test.failure.ignore=true \
        -q \
        2>&1 | tail -5

    echo "  Maven verify complete."

    echo ""
    echo "=== Collecting Surefire + Failsafe XML reports ==="

    SUREFIRE_OUT="$OUT/surefire"
    mkdir -p "$SUREFIRE_OUT"

    SUREFIRE_FILES=0
    SUREFIRE_FILTERS=0

    # Collect from both surefire-reports (unit) and failsafe-reports (integration).
    for report_dir in "$OKAPI"/okapi/filters/*/target/surefire-reports "$OKAPI"/okapi/filters/*/target/failsafe-reports; do
        if [ ! -d "$report_dir" ]; then
            continue
        fi

        # Extract filter name from path (e.g. .../filters/html/target/... → html)
        filter_path="${report_dir%/target/*}"
        filter="$(basename "$filter_path")"

        # Count XML files.
        xml_count=$(find "$report_dir" -name "TEST-*.xml" -type f | wc -l | tr -d ' ')
        if [ "$xml_count" -eq 0 ]; then
            continue
        fi

        # Copy XML files (may merge surefire + failsafe for same filter).
        mkdir -p "$SUREFIRE_OUT/$filter"
        cp "$report_dir"/TEST-*.xml "$SUREFIRE_OUT/$filter/"

        SUREFIRE_FILES=$((SUREFIRE_FILES + xml_count))
    done

    # Count unique filter dirs.
    if [ -d "$SUREFIRE_OUT" ]; then
        SUREFIRE_FILTERS=$(find "$SUREFIRE_OUT" -mindepth 1 -maxdepth 1 -type d | wc -l | tr -d ' ')
    fi

    echo "  Surefire: $SUREFIRE_FILES XML files across $SUREFIRE_FILTERS filters"

    cd "$WORK_DIR"
fi

# ---------------------------------------------------------------------------
# Package the tarball.
# ---------------------------------------------------------------------------

echo ""
echo "=== Packaging tarball ==="

cd "$WORK_DIR"
tar czf "$ASSET_NAME" okapi-testdata/

SIZE=$(du -sh "$ASSET_NAME" | cut -f1)
TOTAL_FILES=$(find okapi-testdata -type f | wc -l | tr -d ' ')
TOTAL_DIRS=$(find okapi-testdata -mindepth 1 -maxdepth 1 -type d | wc -l | tr -d ' ')
echo "  $ASSET_NAME: $SIZE ($TOTAL_FILES files across $TOTAL_DIRS directories)"

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

RELEASE_BODY="Test resources and Surefire reports from Okapi Framework v${OKAPI_VERSION} (https://gitlab.com/okapiframework/okapi/-/tree/v${OKAPI_VERSION}).

Contains test data and Maven test reports for bridge filter integration tests.

- Directories: ${TOTAL_DIRS}
- Files: ${TOTAL_FILES}
- Generated: $(date -u +"%Y-%m-%dT%H:%M:%SZ")
- Script: scripts/publish-okapi-testdata.sh"

gh release create "$RELEASE_TAG" \
    --repo "$GITHUB_REPO" \
    --title "Okapi Test Data v${OKAPI_VERSION}" \
    --notes "$RELEASE_BODY" \
    "$WORK_DIR/$ASSET_NAME"

echo ""
echo "Done. Release: https://github.com/$GITHUB_REPO/releases/tag/$RELEASE_TAG"
