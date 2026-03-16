#!/usr/bin/env bash
#
# publish-okapi-surefire.sh — Build Okapi and publish Surefire/Failsafe reports.
#
# Usage:
#   ./scripts/publish-okapi-surefire.sh [OKAPI_VERSION]
#
# Clones the Okapi Framework from GitLab at the specified version tag, runs
# Maven verify to generate Surefire/Failsafe XML test reports, packages them
# into a tarball, and uploads it as a GitHub release asset.
#
# Arguments:
#   OKAPI_VERSION  — Okapi Framework version (default: 1.48.0). Used as the
#                    GitLab tag (v1.48.0) and the GitHub release tag
#                    (okapi-surefire-{version}).
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
RELEASE_TAG="okapi-surefire-${OKAPI_VERSION}"
ASSET_NAME="okapi-surefire.tar.gz"

# Resolve token.
GITHUB_TOKEN="${GITHUB_TOKEN:-$(gh auth token 2>/dev/null || true)}"
if [ -z "$GITHUB_TOKEN" ] && [ "${SKIP_PUBLISH:-}" = "" ]; then
    echo "ERROR: No GitHub token. Set GITHUB_TOKEN or log in with gh auth login." >&2
    exit 1
fi

# Work in a temp directory.
WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT

echo "=== Cloning Okapi Framework @ ${GITLAB_TAG} ==="

cd "$WORK_DIR"

# Full clone — Maven needs all source to build and run tests.
echo "  Cloning ${GITLAB_TAG}..."
git clone --depth=1 --branch "${GITLAB_TAG}" "${GITLAB_REPO}" okapi --quiet
cd okapi

echo "  Clone complete."

# ---------------------------------------------------------------------------
# Run Maven verify to generate Surefire/Failsafe XML reports.
# ---------------------------------------------------------------------------

echo ""
echo "=== Running Maven verify to generate Surefire reports ==="
echo "  This may take several minutes..."

# Use -fae (fail-at-end) so Maven builds as many modules as possible.
# Ignore exit code — some upstream modules may fail to compile (e.g. javacc
# plugin bugs) but we still want to collect reports from modules that succeeded.
mvn -B verify \
    -T4 \
    -fae \
    -Dmaven.test.failure.ignore=true \
    2>&1 | tail -20 || true

echo "  Maven verify complete."

# ---------------------------------------------------------------------------
# Collect Surefire/Failsafe XML reports from ALL modules.
# ---------------------------------------------------------------------------

echo ""
echo "=== Collecting Surefire + Failsafe XML reports ==="

OKAPI_ROOT="$WORK_DIR/okapi"
SUREFIRE_OUT="$WORK_DIR/okapi-surefire"
mkdir -p "$SUREFIRE_OUT"

SUREFIRE_FILES=0
SUREFIRE_MODULES=0

# Collect from filter modules.
for report_dir in "$OKAPI_ROOT"/okapi/filters/*/target/surefire-reports "$OKAPI_ROOT"/okapi/filters/*/target/failsafe-reports; do
    if [ ! -d "$report_dir" ]; then
        continue
    fi

    # Extract module name from path (e.g. .../filters/html/target/... -> html)
    module_path="${report_dir%/target/*}"
    module="$(basename "$module_path")"

    xml_count=$(find "$report_dir" -name "TEST-*.xml" -type f | wc -l | tr -d ' ')
    if [ "$xml_count" -eq 0 ]; then
        continue
    fi

    mkdir -p "$SUREFIRE_OUT/$module"
    cp "$report_dir"/TEST-*.xml "$SUREFIRE_OUT/$module/"

    SUREFIRE_FILES=$((SUREFIRE_FILES + xml_count))
done

# Collect from integration-tests/okapi.
for report_dir in "$OKAPI_ROOT"/integration-tests/okapi/target/surefire-reports "$OKAPI_ROOT"/integration-tests/okapi/target/failsafe-reports; do
    if [ ! -d "$report_dir" ]; then
        continue
    fi

    xml_count=$(find "$report_dir" -name "TEST-*.xml" -type f | wc -l | tr -d ' ')
    if [ "$xml_count" -eq 0 ]; then
        continue
    fi

    mkdir -p "$SUREFIRE_OUT/integration-tests"
    cp "$report_dir"/TEST-*.xml "$SUREFIRE_OUT/integration-tests/"

    SUREFIRE_FILES=$((SUREFIRE_FILES + xml_count))
done

# Count unique module dirs.
if [ -d "$SUREFIRE_OUT" ]; then
    SUREFIRE_MODULES=$(find "$SUREFIRE_OUT" -mindepth 1 -maxdepth 1 -type d | wc -l | tr -d ' ')
fi

echo "  Surefire: $SUREFIRE_FILES XML files across $SUREFIRE_MODULES modules"

# ---------------------------------------------------------------------------
# Package the tarball.
# ---------------------------------------------------------------------------

echo ""
echo "=== Packaging tarball ==="

cd "$WORK_DIR"
tar czf "$ASSET_NAME" okapi-surefire/

SIZE=$(du -sh "$ASSET_NAME" | cut -f1)
echo "  $ASSET_NAME: $SIZE ($SUREFIRE_FILES files across $SUREFIRE_MODULES modules)"

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

RELEASE_BODY="Surefire/Failsafe test reports from Okapi Framework v${OKAPI_VERSION} (https://gitlab.com/okapiframework/okapi/-/tree/v${OKAPI_VERSION}).

Contains Maven test XML reports organized by module under okapi-surefire/{module}/TEST-*.xml.

- Modules: ${SUREFIRE_MODULES}
- Report files: ${SUREFIRE_FILES}
- Generated: $(date -u +"%Y-%m-%dT%H:%M:%SZ")
- Script: scripts/publish-okapi-surefire.sh"

gh release create "$RELEASE_TAG" \
    --repo "$GITHUB_REPO" \
    --title "Okapi Surefire Reports v${OKAPI_VERSION}" \
    --notes "$RELEASE_BODY" \
    "$WORK_DIR/$ASSET_NAME"

echo ""
echo "Done. Release: https://github.com/$GITHUB_REPO/releases/tag/$RELEASE_TAG"
