#!/bin/bash
# Compose docs/ from the Tier-2 AI overlay (doc-overlay/).
#
# This is the new producer of the committed docs/{filters,steps}/*.json that the
# assemble step copies into per-capability doc.json. It replaces the wiki-parse
# bundle path as the source of truth: docs/ is now a *build product* of the
# committed doc-overlay/.
#
# Responsibilities:
#   1. Validate each overlay file against the overlay contract (jq invariants).
#   2. Emit docs/{filters,steps}/<id>.json in the doc.json shape, carrying an
#      x-provenance block with a derived per-field tier map (ai / human-reviewed;
#      schema-derived facts remain Tier-1 'okapi' and live in schema.json).
#   3. Resolve sub-configuration aliases (doc-overlay/aliases.json): inherit the
#      parent filter's overlay and deep-merge the sub-config's tailored delta.
#   4. Regenerate docs/property-suggestions.json and docs/metadata.json.
#   5. Emit docs/provenance.json — an auditable okapi-vs-ai-vs-human report.
#
# Preserves docs/concepts.json (hand-curated, owned by bundle-docs.sh).
#
# Usage: ./scripts/compose-docs.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"
cd "$ROOT_DIR"

OVERLAY_DIR="doc-overlay"
DOCS_DIR="docs"
ALIASES_FILE="$OVERLAY_DIR/aliases.json"
WIKI_BASE_URL="https://okapiframework.org/wiki/index.php/"
GENERATED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

ALLOWED_KEYS='["$schema","id","kind","name","overview","limitations","processingNotes","examples","parameters","propertySuggestions","fullDoc","wikiUrl","aliasOf","x-provenance"]'

if [ ! -d "$OVERLAY_DIR/filters" ]; then
    echo "Error: $OVERLAY_DIR/filters not found. Run migrate-docs-to-overlay.sh first." >&2
    exit 1
fi

errors=0

# ── validate: required invariants from overlay.schema.json, checked via jq ────
validate_overlay() {
    local file="$1"
    local msg
    msg=$(jq -r --argjson allowed "$ALLOWED_KEYS" '
        [
          (if (.id // "") == "" then "missing id" else empty end),
          (if (.kind|IN("filter","step")|not) then "kind must be filter|step" else empty end),
          (if (.overview // "") == "" then "missing/empty overview" else empty end),
          (if (.["x-provenance"].generator // "") == "" then "missing x-provenance.generator" else empty end),
          (if ((.["x-provenance"].grounding // []) | length) == 0 then "missing x-provenance.grounding" else empty end),
          (if (.["x-provenance"].reviewed | type) != "boolean" then "x-provenance.reviewed must be boolean" else empty end),
          ((keys - $allowed)[] | "unknown key: " + .)
        ] | join("; ")
    ' "$file" 2>&1) || { echo "  ✗ $file: invalid JSON"; return 1; }
    if [ -n "$msg" ]; then
        echo "  ✗ $(basename "$file"): $msg"
        return 1
    fi
    return 0
}

# ── compose: overlay JSON (on stdin) → doc.json shape (stdout) ─────────────────
# Args: id, kind. Reads merged overlay object from stdin.
compose_doc() {
    local id="$1" kind="$2"
    local id_field="filterId"
    [ "$kind" = "step" ] && id_field="stepId"
    jq --arg id "$id" --arg idfield "$id_field" '
        ( if (.["x-provenance"].reviewed == true) then "human-reviewed" else "ai" end ) as $tier
        | {
            filterName: .name,
            ($idfield): $id,
            overview,
            limitations,
            processingNotes,
            examples,
            parameters,
            propertySuggestions,
            fullDoc,
            wikiUrl,
            "x-provenance": (
              (.["x-provenance"]) + {
                fields: (
                  [ (if (.overview // "") != "" then {key:"overview", value:$tier} else empty end),
                    (if ((.limitations // []) | length) > 0 then {key:"limitations", value:$tier} else empty end),
                    (if ((.processingNotes // []) | length) > 0 then {key:"processingNotes", value:$tier} else empty end),
                    (if ((.examples // []) | length) > 0 then {key:"examples", value:$tier} else empty end),
                    ( (.parameters // {}) | keys[] | {key:("parameters." + .), value:$tier} )
                  ] | from_entries
                ),
                schemaFields: "okapi"
              }
            )
          }
        | with_entries(select(.value != null))
    '
}

rm -rf "$DOCS_DIR/filters" "$DOCS_DIR/steps"
mkdir -p "$DOCS_DIR/filters" "$DOCS_DIR/steps"

# Alias keys (sub-configs handled in the alias pass, skipped in the main pass).
ALIAS_KEYS=$(jq -r '.filters // {} | keys[]' "$ALIASES_FILE" 2>/dev/null || true)
is_alias() { echo "$ALIAS_KEYS" | grep -qx "$1"; }

emit_md() {
    local file="$1" out="$2"
    local full_doc
    full_doc=$(jq -r '.fullDoc // empty' "$file")
    if [ -n "$full_doc" ]; then
        printf '%b\n' "$full_doc" > "$out"
    fi
    return 0
}

filter_count=0
# ── filters: direct (non-alias) ───────────────────────────────────────────────
for file in "$OVERLAY_DIR"/filters/*.json; do
    [ -e "$file" ] || continue
    id=$(basename "$file" .json)
    is_alias "$id" && continue
    validate_overlay "$file" || { errors=$((errors+1)); continue; }
    compose_doc "$id" "filter" < "$file" > "$DOCS_DIR/filters/${id}.json"
    emit_md "$DOCS_DIR/filters/${id}.json" "$DOCS_DIR/filters/${id}.md"
    filter_count=$((filter_count+1))
done

# ── filters: sub-configuration aliases ────────────────────────────────────────
alias_count=0
while IFS= read -r sub; do
    [ -z "$sub" ] && continue
    parent=$(jq -r --arg s "$sub" '.filters[$s]' "$ALIASES_FILE")
    parent_file="$OVERLAY_DIR/filters/${parent}.json"
    if [ ! -f "$parent_file" ]; then
        echo "  ✗ alias $sub: parent overlay $parent not found"; errors=$((errors+1)); continue
    fi
    sub_file="$OVERLAY_DIR/filters/${sub}.json"
    if [ -f "$sub_file" ]; then
        validate_overlay "$sub_file" || { errors=$((errors+1)); continue; }
        # parent ⊕ sub (recursive merge: parameters union, sub overrides scalars/arrays it sets)
        merged=$(jq -s --arg subid "$sub" --arg parentid "$parent" '
            .[0] as $parent | .[1] as $sub |
            ($parent * $sub)
            | .id = $subid | .aliasOf = $parentid
            | .["x-provenance"].generator = ($sub["x-provenance"].generator // $parent["x-provenance"].generator)
            | .["x-provenance"].grounding =
                (($parent["x-provenance"].grounding // []) + ($sub["x-provenance"].grounding // []) + ["alias-parent:" + $parentid] | unique)
        ' "$parent_file" "$sub_file")
    else
        # No tailored delta: inherit the parent verbatim, marked as alias-derived.
        merged=$(jq --slurpfile p "$parent_file" -n '
            $p[0]
            | .id = "'"$sub"'" | .aliasOf = "'"$parent"'"
            | .["x-provenance"].generator = "alias"
            | .["x-provenance"].grounding = ((.["x-provenance"].grounding // []) + ["alias-parent:'"$parent"'"] | unique)
        ')
    fi
    echo "$merged" | compose_doc "$sub" "filter" > "$DOCS_DIR/filters/${sub}.json"
    emit_md "$DOCS_DIR/filters/${sub}.json" "$DOCS_DIR/filters/${sub}.md"
    alias_count=$((alias_count+1))
done <<< "$ALIAS_KEYS"

# ── steps ─────────────────────────────────────────────────────────────────────
step_count=0
for file in "$OVERLAY_DIR"/steps/*.json; do
    [ -e "$file" ] || continue
    id=$(basename "$file" .json)
    validate_overlay "$file" || { errors=$((errors+1)); continue; }
    compose_doc "$id" "step" < "$file" > "$DOCS_DIR/steps/${id}.json"
    emit_md "$DOCS_DIR/steps/${id}.json" "$DOCS_DIR/steps/${id}.md"
    step_count=$((step_count+1))
done

if [ "$errors" -gt 0 ]; then
    echo ""
    echo "Compose failed: $errors overlay file(s) invalid." >&2
    exit 1
fi

# ── property-suggestions.json (aggregated from overlays) ──────────────────────
jq -s 'reduce .[] as $f ({};
        if ($f.propertySuggestions // {}) | length > 0
        then .[$f.id] = $f.propertySuggestions else . end)' \
    "$OVERLAY_DIR"/filters/*.json "$OVERLAY_DIR"/steps/*.json \
    > "$DOCS_DIR/property-suggestions.json"

# ── metadata.json (aliases + generation info) ─────────────────────────────────
jq -n --arg ts "$GENERATED_AT" --arg wiki "$WIKI_BASE_URL" \
    --argjson aliases "$(jq '.filters // {}' "$ALIASES_FILE")" \
    '{generatedAt: $ts, wikiBaseUrl: $wiki, aliases: $aliases}' \
    > "$DOCS_DIR/metadata.json"

# ── provenance.json (the auditable okapi-vs-ai-vs-human report) ───────────────
jq -s --arg ts "$GENERATED_AT" '
    map({
      id, kind: (if .filterId then "filter" else "step" end),
      generator: .["x-provenance"].generator,
      reviewed: (.["x-provenance"].reviewed // false),
      aliasOf: (.aliasOf // null),
      grounding: (.["x-provenance"].grounding // []),
      aiFields: ((.["x-provenance"].fields // {}) | length)
    } | with_entries(select(.value != null))) as $caps
    | {
        generatedAt: $ts,
        summary: {
          total: ($caps | length),
          filters: ($caps | map(select(.kind=="filter")) | length),
          steps: ($caps | map(select(.kind=="step")) | length),
          reviewed: ($caps | map(select(.reviewed)) | length),
          unreviewedAi: ($caps | map(select(.reviewed|not)) | length),
          wikiParse: ($caps | map(select(.generator=="claude/wiki-parse")) | length),
          aliasDerived: ($caps | map(select(.generator=="alias")) | length)
        },
        capabilities: ($caps | sort_by(.kind, .id))
      }
' "$DOCS_DIR"/filters/*.json "$DOCS_DIR"/steps/*.json > "$DOCS_DIR/provenance.json"

echo "Composed docs/ from $OVERLAY_DIR/"
echo "  Filters: $filter_count direct + $alias_count alias-derived"
echo "  Steps:   $step_count"
echo "  Reports: provenance.json, property-suggestions.json, metadata.json"
echo "  (concepts.json preserved)"
