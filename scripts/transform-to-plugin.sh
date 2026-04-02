#!/bin/bash
# Transform okapi-data/ to neokapi plugin format.
#
# Converts okapi-native extraction output into the neokapi plugin directory
# structure using the neokapi schema language:
#
#   Extension namespaces:
#     ui:*         — UI rendering hints (widget, visible, enabled, layout, etc.)
#     (no prefix)  — neokapi data (formatMeta, toolMeta, presets)
#     x-okapi-*    — Okapi bridge internals (flatten-path, format, kind)
#
#   Key transforms:
#     x-filter           → formatMeta + presets (top-level)
#     x-step             → toolMeta (from step-metadata.json)
#     x-editor           → ui:widget + ui:widget-options + ui:enabled + ui:layout
#     x-showIf           → ui:visible
#     x-enumLabels+enum  → options (consolidated labeled enum)
#     x-enumDescriptions → ui:enum-descriptions
#     x-flattenPath      → x-okapi-flatten-path
#     x-okapiFormat      → x-okapi-format
#     x-kind             → x-okapi-kind
#     x-placeholder      → ui:placeholder
#     x-widget           → ui:widget
#     x-presets           → ui:presets
#     x-path             → x-path (preserved, merged with path browse metadata)
#     enables            → resolved to ui:enabled on target properties
#     enabledBy           → ui:enabled (converted from simple to ConditionExpr)
#     options            → options (passed through)
#     group              → removed (use nested objects instead)
#     layout             → ui:layout (consolidated)
#
# Usage: ./scripts/transform-to-plugin.sh <okapi-version> <bridge-version>

set -euo pipefail

if [ $# -lt 2 ]; then
    echo "Usage: $0 <okapi-version> <bridge-version>"
    exit 1
fi

OKAPI_VERSION="$1"
BRIDGE_VERSION="$2"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"
cd "$ROOT_DIR"

INPUT_DIR="okapi-data/${OKAPI_VERSION}"
OUTPUT_DIR="dist/plugin"
STEP_METADATA="schemagen/step-metadata.json"

if [ ! -d "$INPUT_DIR" ]; then
    echo "Error: $INPUT_DIR not found. Run 'make assemble V=${OKAPI_VERSION}' first." >&2
    exit 1
fi

if [ ! -f "$STEP_METADATA" ]; then
    echo "Error: $STEP_METADATA not found." >&2
    exit 1
fi

rm -rf "$OUTPUT_DIR"
mkdir -p "$OUTPUT_DIR/formats" "$OUTPUT_DIR/tools" "$OUTPUT_DIR/docs"

echo "Transforming okapi-data to neokapi plugin format..."

# ── jq function: rename property-level extensions recursively ─────────────
# Applied to all properties in both format and tool schemas.
RENAME_PROPS='
def rename_ui_extensions:
  if type == "object" then
    # Rename known extensions
    (if has("x-widget") then .["ui:widget"] = .["x-widget"] | del(.["x-widget"]) else . end) |
    (if has("x-placeholder") then .["ui:placeholder"] = .["x-placeholder"] | del(.["x-placeholder"]) else . end) |
    (if has("x-presets") then .["ui:presets"] = .["x-presets"] | del(.["x-presets"]) else . end) |
    # Consolidate enums: x-enumLabels + enum → options array
    (if has("x-enumLabels") and has("enum") and ((.enum | length) == (.["x-enumLabels"] | length)) then
      .options = ([.enum, .["x-enumLabels"]] | transpose | map({value: .[0], label: .[1]})) |
      del(.enum) | del(.["x-enumLabels"])
    elif has("x-enumLabels") then
      del(.["x-enumLabels"])
    else . end) |
    (if has("x-enumDescriptions") then .["ui:enum-descriptions"] = .["x-enumDescriptions"] | del(.["x-enumDescriptions"]) else . end) |
    (if has("x-flattenPath") then .["x-okapi-flatten-path"] = .["x-flattenPath"] | del(.["x-flattenPath"]) else . end) |
    (if has("x-okapiFormat") then .["x-okapi-format"] = .["x-okapiFormat"] | del(.["x-okapiFormat"]) else . end) |
    (if has("x-order") then .["ui:order"] = .["x-order"] | del(.["x-order"]) else . end) |
    (if has("x-introducedInOkapi") then .["ui:introduced-in"] = .["x-introducedInOkapi"] | del(.["x-introducedInOkapi"]) else . end) |
    # Transform x-showIf → ui:visible
    (if has("x-showIf") then
      .["ui:visible"] = (
        .["x-showIf"] |
        if has("empty") then {field: .field, empty: .empty}
        elif has("value") then {field: .field, eq: .value}
        else .
        end
      ) | del(.["x-showIf"])
    else . end) |
    # Transform x-editor → ui:widget + ui:widget-options + ui:enabled + ui:layout
    (if has("x-editor") then
      (.["x-editor"]) as $ed |
      # Widget mapping
      (if $ed.widget == "text" and ($ed.text.password // false) then .["ui:widget"] = "password"
       elif $ed.widget == "text" and ($ed.text.height // 0) > 1 then .["ui:widget"] = "textarea" | .["ui:widget-options"] = {rows: $ed.text.height}
       elif $ed.widget == "codeFinder" then .["ui:widget"] = "code-finder"
       elif $ed.widget == "path" then .["ui:widget"] = "file-picker" | .["ui:widget-options"] = ($ed.path // {} | with_entries(select(.value != null)))
       elif $ed.widget == "folder" then .["ui:widget"] = "folder-picker" | .["ui:widget-options"] = ($ed.folder // {} | with_entries(select(.value != null)))
       elif $ed.widget == "checkList" then .["ui:widget"] = "checklist" | .["ui:widget-options"] = ($ed.checkList // {} | with_entries(select(.value != null)))
       elif $ed.widget == "select" then .["ui:widget"] = "select"
       else . end) |
      # enabledBy → ui:enabled
      (if $ed.enabledBy then
        .["ui:enabled"] = (
          if $ed.enabledBy.enabledWhenSelected then
            {field: $ed.enabledBy.parameter, eq: true}
          else
            {not: {field: $ed.enabledBy.parameter, eq: true}}
          end
        )
      else . end) |
      # layout → ui:layout
      (if $ed.layout then
        .["ui:layout"] = (
          {} |
          (if $ed.layout.withLabel == false then .hideLabel = true else . end) |
          (if $ed.layout.vertical then .vertical = true else . end) |
          if . == {} then null else . end
        ) | if .["ui:layout"] == null then del(.["ui:layout"]) else . end
      else . end) |
      del(.["x-editor"])
    else . end) |
    # Consolidate standalone enabledBy → ui:enabled (from overrides, not x-editor)
    (if has("enabledBy") and (has("ui:enabled") | not) then
      .["ui:enabled"] = (
        if .enabledBy.enabledWhenSelected then
          {field: .enabledBy.parameter, eq: true}
        else
          {not: {field: .enabledBy.parameter, eq: true}}
        end
      ) | del(.enabledBy)
    elif has("enabledBy") then del(.enabledBy)
    else . end) |
    # Consolidate standalone layout → ui:layout (from overrides)
    (if has("layout") and (has("ui:layout") | not) then
      .["ui:layout"] = (
        .layout |
        (if has("withLabel") then
          if .withLabel == false then {hideLabel: true} else {} end
        else {} end) +
        (if .vertical then {vertical: true} else {} end)
      ) | del(.layout) |
      if .["ui:layout"] == {} then del(.["ui:layout"]) else . end
    elif has("layout") then del(.layout)
    else . end) |
    # Convert x-enabledBy → ui:enabled (for $defs properties)
    (if has("x-enabledBy") and (has("ui:enabled") | not) then
      .["ui:enabled"] = {field: .["x-enabledBy"], eq: true} | del(.["x-enabledBy"])
    elif has("x-enabledBy") then del(.["x-enabledBy"])
    else . end) |
    # Rename x-enables → enables (consumed by the schema-level enables resolution)
    (if has("x-enables") then .enables = .["x-enables"] | del(.["x-enables"]) else . end) |
    # Merge path browse metadata into x-path (if both exist)
    (if has("path") and has("x-path") then
      .["x-path"] = .["x-path"] + .path | del(.path)
    elif has("path") then
      .["x-path"] = (.["x-path"] // {}) + .path | del(.path)
    else . end) |
    # Remove group field (use nested objects instead)
    del(.group) |
    # Recurse into nested properties
    (if has("properties") then .properties |= map_values(rename_ui_extensions) else . end) |
    (if has("items") and (.items | type) == "object" then .items |= rename_ui_extensions else . end) |
    (if has("$defs") then .["$defs"] |= map_values(rename_ui_extensions) else . end) |
    (if has("prefixItems") then .prefixItems |= map(rename_ui_extensions) else . end) |
    (if has("oneOf") then .oneOf |= map(rename_ui_extensions) else . end)
  else .
  end;
'

# ============================================================================
# Format schemas (filter -> format)
# ============================================================================

FORMAT_CAPS="[]"

for filter_dir in "$INPUT_DIR"/filters/*/; do
    [ -d "$filter_dir" ] || continue
    filter_id=$(basename "$filter_dir")
    schema_file="$filter_dir/schema.json"
    [ -f "$schema_file" ] || continue

    mkdir -p "$OUTPUT_DIR/formats/${filter_id}"

    # Transform filter schema → neokapi format schema
    jq "${RENAME_PROPS}"'
        # x-filter → formatMeta (strip Java internals)
        (if .["x-filter"] then
            .formatMeta = (
                .["x-filter"] |
                del(.class, .serializationFormat, .parametersRaw, .parametersLocation)
            )
        else .
        end) |
        # Extract configurations → top-level presets map
        (if .formatMeta.configurations then
            .presets = (
                [.formatMeta.configurations[] | {key: .configId, value: .parameters}]
                | from_entries
            ) |
            .formatMeta |= del(.configurations)
        else .
        end) |
        del(.["x-filter"]) |
        # Okapi metadata → x-okapi-* namespace
        (if has("x-kind") then .["x-okapi-kind"] = .["x-kind"] | del(.["x-kind"]) else . end) |
        (if has("x-apiVersion") then .["x-okapi-api-version"] = .["x-apiVersion"] | del(.["x-apiVersion"]) else . end) |
        (if has("x-baseVersion") then .["x-okapi-base-version"] = .["x-baseVersion"] | del(.["x-baseVersion"]) else . end) |
        (if has("x-baseHash") then .["x-okapi-base-hash"] = .["x-baseHash"] | del(.["x-baseHash"]) else . end) |
        (if has("x-compositeHash") then .["x-okapi-composite-hash"] = .["x-compositeHash"] | del(.["x-compositeHash"]) else . end) |
        (if has("x-introducedInOkapi") then .["ui:introduced-in"] = .["x-introducedInOkapi"] | del(.["x-introducedInOkapi"]) else . end) |
        # Rename x-groups → ui:groups
        (if has("x-groups") then .["ui:groups"] = .["x-groups"] | del(.["x-groups"]) else . end) |
        # Rewrite $id namespace
        (if has("$id") then .["$id"] = (.["$id"] | gsub("okapiframework\\.org"; "neokapi.dev")) else . end) |
        # Rename property extensions recursively
        (if has("properties") then .properties |= map_values(rename_ui_extensions) else . end) |
        (if has("$defs") then .["$defs"] |= map_values(rename_ui_extensions) else . end) |
        # Resolve enables → ui:enabled on target properties
        # Helper: resolve enables within a properties object
        def resolve_enables:
          (to_entries | map(select(.value | type == "object" and .enables != null)) |
           map({master: .key, targets: .value.enables})) as $enables_list |
          if ($enables_list | length) > 0 then
            reduce ($enables_list[]) as $e (.;
              reduce ($e.targets[]) as $t (.;
                if has($t) and (.[$t] | type == "object") and (.[$t]["ui:enabled"] == null) then
                  .[$t]["ui:enabled"] = {field: $e.master, eq: true}
                else . end
              )
            ) |
            with_entries(if (.value | type == "object") then .value |= del(.enables) else . end)
          else . end;
        # Resolve in root properties
        (if has("properties") then .properties |= resolve_enables else . end) |
        # Resolve in nested objects within properties (e.g., grouped objects)
        (if has("properties") then
          .properties |= with_entries(
            if (.value | type == "object" and has("properties")) then
              .value.properties |= resolve_enables
            else . end
          )
        else . end) |
        # Resolve in $defs (e.g., inlineCodes, codeFinderRules)
        (if has("$defs") then
          .["$defs"] |= with_entries(
            if (.value | type == "object" and has("properties")) then
              .value.properties |= resolve_enables
            else . end
          )
        else . end) |
        # Flatten $defs: inline all $ref references, then remove $defs.
        # This makes plugin schemas self-contained with no indirection.
        def inline_refs($defs):
          if type == "object" then
            if has("$ref") then
              (.["$ref"] | ltrimstr("#/$defs/")) as $rname |
              ($defs[$rname] // {}) as $resolved |
              ($resolved + (. | del(.["$ref"]))) | inline_refs($defs)
            else map_values(inline_refs($defs))
            end
          elif type == "array" then map(inline_refs($defs))
          else . end;
        (if has("$defs") then
          .["$defs"] as $d |
          (if has("properties") then .properties |= inline_refs($d) else . end) |
          del(.["$defs"])
        else . end)
    ' "$schema_file" > "$OUTPUT_DIR/formats/${filter_id}/schema.json"

    # Extract presets into separate files
    if jq -e '.presets // empty | keys | length > 0' "$OUTPUT_DIR/formats/${filter_id}/schema.json" > /dev/null 2>&1; then
        mkdir -p "$OUTPUT_DIR/formats/${filter_id}/presets"
        jq -r '.presets | to_entries[] | @base64' "$OUTPUT_DIR/formats/${filter_id}/schema.json" | while read -r entry; do
            preset_id=$(echo "$entry" | base64 -d | jq -r '.key')
            echo "$entry" | base64 -d | jq '.value' > "$OUTPUT_DIR/formats/${filter_id}/presets/${preset_id}.json"
        done
    fi

    # Transform filter doc → format doc
    if [ -f "$filter_dir/doc.json" ]; then
        jq '
            if .filterId then .formatId = .filterId | del(.filterId) else . end |
            if .filterName then .formatName = .filterName | del(.filterName) else . end
        ' "$filter_dir/doc.json" > "$OUTPUT_DIR/formats/${filter_id}/doc.json"
    fi

    # Build capability entry for manifest
    cap=$(jq -n --arg id "$filter_id" \
        --arg schema "formats/${filter_id}/schema.json" \
        --arg doc "formats/${filter_id}/doc.json" \
        --arg presets_dir "formats/${filter_id}/presets/" \
        --argjson format_schema "$(cat "$OUTPUT_DIR/formats/${filter_id}/schema.json")" '
        {
            type: "format",
            id: $id,
            name: ($format_schema.formatMeta.id // $id),
            display_name: ($format_schema.title // $id),
            capabilities: ["read", "write"],
            mime_types: ($format_schema.formatMeta.mimeTypes // []),
            extensions: ($format_schema.formatMeta.extensions // []),
            schema: $schema
        }
        | with_entries(select(.value != null and .value != []))
    ')
    if [ -f "$filter_dir/doc.json" ]; then
        cap=$(echo "$cap" | jq --arg doc "formats/${filter_id}/doc.json" '. + {doc: $doc}')
    fi
    if [ -d "$OUTPUT_DIR/formats/${filter_id}/presets" ]; then
        cap=$(echo "$cap" | jq --arg pd "formats/${filter_id}/presets/" '. + {presets_dir: $pd}')
    fi

    FORMAT_CAPS=$(echo "$FORMAT_CAPS" | jq --argjson c "$cap" '. + [$c]')
done

FORMAT_COUNT=$(echo "$FORMAT_CAPS" | jq 'length')
echo "  Formats: $FORMAT_COUNT"

# ============================================================================
# Tool schemas (step -> tool)
# ============================================================================

TOOL_CAPS="[]"

for step_dir in "$INPUT_DIR"/steps/*/; do
    [ -d "$step_dir" ] || continue
    step_id=$(basename "$step_dir")
    schema_file="$step_dir/schema.json"
    [ -f "$schema_file" ] || continue

    mkdir -p "$OUTPUT_DIR/tools/${step_id}"

    # Transform step schema → neokapi tool schema
    jq "${RENAME_PROPS}"'
        # Remove x-step, add toolMeta from step-metadata.json
        del(.["x-step"]) |
        # Rename x-groups → ui:groups
        (if has("x-groups") then .["ui:groups"] = .["x-groups"] | del(.["x-groups"]) else . end) |
        # Rename property extensions recursively
        (if has("properties") then .properties |= map_values(rename_ui_extensions) else . end) |
        (if has("$defs") then .["$defs"] |= map_values(rename_ui_extensions) else . end) |
        # Resolve enables → ui:enabled on target properties
        # Helper: resolve enables within a properties object
        def resolve_enables:
          (to_entries | map(select(.value | type == "object" and .enables != null)) |
           map({master: .key, targets: .value.enables})) as $enables_list |
          if ($enables_list | length) > 0 then
            reduce ($enables_list[]) as $e (.;
              reduce ($e.targets[]) as $t (.;
                if has($t) and (.[$t] | type == "object") and (.[$t]["ui:enabled"] == null) then
                  .[$t]["ui:enabled"] = {field: $e.master, eq: true}
                else . end
              )
            ) |
            with_entries(if (.value | type == "object") then .value |= del(.enables) else . end)
          else . end;
        # Resolve in root properties
        (if has("properties") then .properties |= resolve_enables else . end) |
        # Resolve in nested objects within properties (e.g., grouped objects)
        (if has("properties") then
          .properties |= with_entries(
            if (.value | type == "object" and has("properties")) then
              .value.properties |= resolve_enables
            else . end
          )
        else . end) |
        # Resolve in $defs (e.g., inlineCodes, codeFinderRules)
        (if has("$defs") then
          .["$defs"] |= with_entries(
            if (.value | type == "object" and has("properties")) then
              .value.properties |= resolve_enables
            else . end
          )
        else . end) |
        # Flatten $defs: inline all $ref references, then remove $defs.
        # This makes plugin schemas self-contained with no indirection.
        def inline_refs($defs):
          if type == "object" then
            if has("$ref") then
              (.["$ref"] | ltrimstr("#/$defs/")) as $rname |
              ($defs[$rname] // {}) as $resolved |
              ($resolved + (. | del(.["$ref"]))) | inline_refs($defs)
            else map_values(inline_refs($defs))
            end
          elif type == "array" then map(inline_refs($defs))
          else . end;
        (if has("$defs") then
          .["$defs"] as $d |
          (if has("properties") then .properties |= inline_refs($d) else . end) |
          del(.["$defs"])
        else . end)
    ' "$schema_file" | jq --arg sid "$step_id" --slurpfile meta "$STEP_METADATA" '
        if $meta[0][$sid] then
            .toolMeta = $meta[0][$sid]
        else
            .toolMeta = {
                displayName: .title,
                category: "pipeline",
                inputs: ["block"]
            }
        end
    ' > "$OUTPUT_DIR/tools/${step_id}/schema.json"

    # Transform step doc → tool doc
    if [ -f "$step_dir/doc.json" ]; then
        jq '
            if .stepId then .toolId = .stepId | del(.stepId) else . end
        ' "$step_dir/doc.json" > "$OUTPUT_DIR/tools/${step_id}/doc.json"
    fi

    # Build capability entry
    step_meta=$(jq --arg sid "$step_id" '.[$sid] // {}' "$STEP_METADATA")
    cap=$(jq -n --arg id "$step_id" \
        --arg schema "tools/${step_id}/schema.json" \
        --argjson meta "$step_meta" \
        --argjson tool_schema "$(cat "$OUTPUT_DIR/tools/${step_id}/schema.json")" '
        {
            type: "tool",
            id: $id,
            name: ($meta.displayName // $tool_schema.title // $id),
            display_name: ($meta.displayName // $tool_schema.title // $id),
            description: ($meta.description // $tool_schema.description // null),
            category: ($meta.category // "pipeline"),
            inputs: ($meta.inputs // ["block"]),
            outputs: ($meta.outputs // null),
            tags: ($meta.tags // null),
            requires: ($meta.requires // null),
            schema: $schema
        }
        | with_entries(select(.value != null and .value != []))
    ')
    if [ -f "$step_dir/doc.json" ]; then
        cap=$(echo "$cap" | jq --arg doc "tools/${step_id}/doc.json" '. + {doc: $doc}')
    fi

    TOOL_CAPS=$(echo "$TOOL_CAPS" | jq --argjson c "$cap" '. + [$c]')
done

TOOL_COUNT=$(echo "$TOOL_CAPS" | jq 'length')
echo "  Tools: $TOOL_COUNT"

# ============================================================================
# Shared docs
# ============================================================================

if [ -f "$INPUT_DIR/concepts.json" ]; then
    cp "$INPUT_DIR/concepts.json" "$OUTPUT_DIR/docs/concepts.json"
fi

if [ -f "docs/metadata.json" ]; then
    cp docs/metadata.json "$OUTPUT_DIR/docs/metadata.json"
fi

# ============================================================================
# Manifest
# ============================================================================

CAPABILITIES=$(jq -n --argjson f "$FORMAT_CAPS" --argjson t "$TOOL_CAPS" '$f + $t')

jq -n \
    --arg name "okapi" \
    --arg version "$BRIDGE_VERSION" \
    --arg framework_version "$OKAPI_VERSION" \
    --argjson capabilities "$CAPABILITIES" \
    '{
        name: $name,
        version: $version,
        framework_version: $framework_version,
        plugin_type: "bundle",
        install_type: "bridge",
        command: "java",
        args: ["-jar", "neokapi-bridge-jar-with-dependencies.jar"],
        capabilities: $capabilities
    }' > "$OUTPUT_DIR/manifest.json"

echo ""
echo "Plugin output: $OUTPUT_DIR/"
echo "  $FORMAT_COUNT formats, $TOOL_COUNT tools"
echo "  Manifest: $OUTPUT_DIR/manifest.json"
