#!/bin/bash
# Merge base schema with override to create composite
# Usage: merge-schema.sh <base.json> <override.json> <output.json>
#
# If override doesn't exist, copies base as-is
# Supports $include for shared fragments (resolved before merging)
# Walks hierarchical properties to apply field hints (supports dot-paths and auto-resolution)

set -euo pipefail

if [[ $# -lt 3 ]]; then
    echo "Usage: $0 <base.json> <override.json> <output.json>" >&2
    exit 1
fi

BASE="$1"
OVERRIDE="$2"
OUTPUT="$3"

if [[ ! -f "$BASE" ]]; then
    echo "Error: Base file not found: $BASE" >&2
    exit 1
fi

# If no override, just copy base
if [[ ! -f "$OVERRIDE" ]]; then
    cp "$BASE" "$OUTPUT"
    exit 0
fi

OVERRIDE_DIR="$(dirname "$OVERRIDE")"

# Resolve $include references in override
# Merges all included fragments, then overlays the override's own fields
resolve_override() {
    local override_file="$1"
    local override_dir="$2"

    # Check if override has $include
    local includes
    includes=$(jq -r '.["$include"] // empty | .[]?' "$override_file" 2>/dev/null)

    if [[ -z "$includes" ]]; then
        # No includes, return as-is
        cat "$override_file"
        return
    fi

    # Start with empty base (fields only, no groups)
    local merged='{"fields":{}}'

    # Merge each included fragment
    while IFS= read -r include_path; do
        local fragment_file="$override_dir/$include_path"
        if [[ -f "$fragment_file" ]]; then
            merged=$(echo "$merged" | jq --slurpfile frag "$fragment_file" '
                # Merge fields (fragment first, then existing wins)
                .fields = (($frag[0].fields // {}) + .fields)
            ')
        fi
    done <<< "$includes"

    # Overlay the override's own fields on top
    echo "$merged" | jq --slurpfile ov "$override_file" '
        # Merge fields (override wins)
        .fields = (.fields + (($ov[0].fields // {}) | del(.["$include"]))) |
        # Copy through any other top-level keys from override (except groups, $include, $schema)
        . + ($ov[0] | del(.fields, .groups, .["$include"], .["$schema"]))
    '
}

# Resolve includes into a temp file
RESOLVED_OVERRIDE=$(mktemp)
trap "rm -f $RESOLVED_OVERRIDE" EXIT
resolve_override "$OVERRIDE" "$OVERRIDE_DIR" > "$RESOLVED_OVERRIDE"

# Merge resolved override into base
# 1. Walk nested properties to apply field hints (supports hierarchical schemas)
# 2. No x-groups emission — hierarchy IS the grouping
# 3. $defs from base are preserved as-is
jq -s '
  # Transform override field hints to x- prefixed keys
  def map_hints:
    to_entries |
    map(
      if .key == "widget" then {"key": "x-widget", "value": .value}
      elif .key == "placeholder" then {"key": "x-placeholder", "value": .value}
      elif .key == "presets" then {"key": "x-presets", "value": .value}
      elif .key == "order" then {"key": "x-order", "value": .value}
      elif .key == "showIf" then {"key": "x-showIf", "value": .value}
      elif .key == "description" then {"key": "description", "value": .value}
      else {"key": .key, "value": .value}
      end
    ) |
    from_entries;

  # Apply field hints to properties, walking into nested objects
  # $fields: the override fields object
  # Supports: "fieldName" (auto-resolved in nested props) and "group.fieldName" (dot-path)
  def apply_hints($fields):
    if . == null or $fields == null then .
    else
      # Build a lookup of dot-path -> hints for explicit dot-paths
      ($fields | to_entries | map(select(.key | contains("."))) |
        map({key: .key, value: .value}) | from_entries) as $dot_paths |
      # Simple field names (no dot)
      ($fields | to_entries | map(select(.key | contains(".") | not)) |
        map({key: .key, value: .value}) | from_entries) as $simple |

      # First: apply dot-path hints (e.g. "extraction.extractAll")
      (reduce ($dot_paths | to_entries[]) as $dp (.;
        ($dp.key | split(".")) as $parts |
        if ($parts | length) == 2 then
          ($parts[0]) as $group | ($parts[1]) as $field |
          if .[$group].properties[$field] then
            .[$group].properties[$field] |= . + ($dp.value | map_hints)
          else .
          end
        else .
        end
      )) |

      # Second: apply simple field hints — match at root, by x-flattenPath, or search nested groups
      (reduce ($simple | to_entries[]) as $sf (.;
        if .[$sf.key] and (.[$sf.key] | type == "object") and (.[$sf.key] | has("type") or has("$ref")) then
          # Direct match at root level
          .[$sf.key] |= . + ($sf.value | map_hints)
        else
          # Search by x-flattenPath at root level
          (to_entries | map(
            select(.value | type == "object") |
            select(.value["x-flattenPath"] == $sf.key) |
            .key
          ) | first // null) as $flatMatch |
          if $flatMatch then
            .[$flatMatch] |= . + ($sf.value | map_hints)
          else
            # Search nested group properties (by key or x-flattenPath)
            (to_entries | map(
              select(.value | type == "object") |
              select(.value | has("properties")) |
              .key as $gk |
              .value.properties | to_entries | map(
                select(.key == $sf.key or (.value | type == "object" and .["x-flattenPath"] == $sf.key))
              ) | select(length > 0) |
              {group: $gk, field: .[0].key}
            ) | first // null) as $nested |
            if $nested then
              .[$nested.group].properties[$nested.field] |= . + ($sf.value | map_hints)
            else .
            end
          end
        end
      ))
    end;

  .[0] as $base | .[1] as $override |
  $base |

  # Apply field hints into properties (walks nested hierarchy)
  if $override.fields and .properties then
    .properties |= apply_hints($override.fields)
  else .
  end |

  # Apply field hints into $defs (for $ref resolution)
  # When a property uses $ref to #/$defs/X, hints for fields inside that
  # definition should be applied to $defs.X.properties
  if $override.fields and .["$defs"] then
    .["$defs"] |= with_entries(
      if (.value | type == "object" and has("properties")) then
        .value.properties |= apply_hints($override.fields)
      else .
      end
    )
  else .
  end
' "$BASE" "$RESOLVED_OVERRIDE" > "$OUTPUT"
