#!/bin/bash
# Structural self-check for a single doc-overlay file. Does NOT touch shared
# build state (no compose/assemble/transform) — safe to run concurrently.
#
# Verifies the overlay contract (id/kind/overview/x-provenance) and reports, for
# the id's gap entry in .gap-manifest.json, which required property paths are
# still uncovered by this file's parameters{} (keyed by full path OR leaf name).
#
# Usage: ./scripts/check-overlay.sh doc-overlay/filters/okf_vtt.json

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"
cd "$ROOT_DIR"

file="${1:?usage: check-overlay.sh <overlay-file>}"
[ -f "$file" ] || { echo "✗ not found: $file"; exit 1; }
id="$(basename "$file" .json)"

ALLOWED='["$schema","id","kind","name","overview","limitations","processingNotes","examples","parameters","propertySuggestions","fullDoc","wikiUrl","aliasOf","x-provenance"]'

# 1) contract invariants
msg=$(jq -r --argjson allowed "$ALLOWED" '
    [ (if (.id // "")=="" then "missing id" else empty end),
      (if (.kind|IN("filter","step")|not) then "kind must be filter|step" else empty end),
      (if (.overview // "")=="" then "missing/empty overview" else empty end),
      (if (.["x-provenance"].generator // "")=="" then "missing x-provenance.generator" else empty end),
      (if ((.["x-provenance"].grounding // [])|length)==0 then "missing x-provenance.grounding" else empty end),
      (if (.["x-provenance"].reviewed|type)!="boolean" then "reviewed must be boolean" else empty end),
      ((keys - $allowed)[] | "unknown key: " + .)
    ] | join("; ")' "$file" 2>&1) || { echo "✗ $id: invalid JSON"; exit 1; }
if [ -n "$msg" ]; then echo "✗ $id: $msg"; exit 1; fi

# 2) gap coverage (advisory)
if [ -f ".gap-manifest.json" ]; then
    jq -r --arg id "$id" --slurpfile ov "$file" '
        .[$id] // {} as $g
        | ($ov[0].parameters // {} | keys) as $have
        | ($g.properties // []) as $need
        | [ $need[] | . as $p | ($p | split(".") | last) as $l
            | select(($have | index($p)) == null and ($have | index($l)) == null) ] as $missing
        | "  needs: overview=\($g.needsOverview // false) examples=\($g.needsExamples // false) properties=\($need|length)",
          (if ($missing|length)>0
           then "  ⚠ uncovered properties (\($missing|length)): \($missing|join(", "))"
           else "  ✓ all gap properties covered" end)
    ' .gap-manifest.json
fi
echo "✓ $id: contract OK"
