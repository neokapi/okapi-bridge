#!/bin/bash
# One-time migration: lift the existing committed docs/{filters,steps}/*.json
# (produced by the wiki-parse pipeline) into the Tier-2 AI overlay at
# doc-overlay/{filters,steps}/<id>.json, stamping each with x-provenance.
#
# After this runs, docs/ becomes a *build product* of compose-docs.sh reading
# doc-overlay/. The overlay is the durable, committed home of AI-authored prose;
# its provenance records that this content originated from the Claude wiki parse.
#
# Idempotent: re-running re-derives overlay files from docs/. Safe to run once,
# commit, then hand authoring over to doc-overlay/ directly.
#
# Usage: ./scripts/migrate-docs-to-overlay.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"
cd "$ROOT_DIR"

DOCS_DIR="docs"
OVERLAY_DIR="doc-overlay"
TODAY="$(date -u +%Y-%m-%d)"

if [ ! -d "$DOCS_DIR/filters" ]; then
    echo "Error: $DOCS_DIR/filters not found." >&2
    exit 1
fi

mkdir -p "$OVERLAY_DIR/filters" "$OVERLAY_DIR/steps"

migrate_one() {
    local src="$1" kind="$2" outdir="$3"
    local id
    id="$(basename "$src" .json)"

    # Normalise to overlay shape: neutral id/kind/name, drop the legacy
    # filterId/stepId/filterName, keep all authored content, stamp provenance.
    jq --arg id "$id" --arg kind "$kind" --arg today "$TODAY" '
        {
          "$schema": "../overlay.schema.json",
          id: $id,
          kind: $kind,
          name: (.filterName // .stepName // null),
          overview: (.overview // ""),
          limitations: .limitations,
          processingNotes: .processingNotes,
          examples: .examples,
          parameters: .parameters,
          propertySuggestions: .propertySuggestions,
          fullDoc: .fullDoc,
          wikiUrl: .wikiUrl,
          "x-provenance": {
            generator: "claude/wiki-parse",
            grounding: (
              ((.wikiUrl // "") | if . != "" then ["wiki:" + .] else [] end) + ["schema"]
            ),
            authoredAt: $today,
            reviewed: false
          }
        }
        | with_entries(select(.value != null))
    ' "$src" > "$outdir/${id}.json"
}

filter_count=0
for f in "$DOCS_DIR"/filters/*.json; do
    [ -e "$f" ] || continue
    migrate_one "$f" "filter" "$OVERLAY_DIR/filters"
    ((filter_count++))
done

step_count=0
for f in "$DOCS_DIR"/steps/*.json; do
    [ -e "$f" ] || continue
    migrate_one "$f" "step" "$OVERLAY_DIR/steps"
    ((step_count++))
done

echo "Migrated $filter_count filters + $step_count steps into $OVERLAY_DIR/"
echo "Next: run ./scripts/compose-docs.sh to regenerate docs/ from the overlay."
