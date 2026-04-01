#!/bin/bash
# Transform okapi-data/ to neokapi plugin format.
#
# Converts okapi-native extraction output into the neokapi plugin directory
# structure using neokapi vocabulary throughout:
#   - filters -> formats
#   - steps -> tools
#   - x-filter -> x-format
#   - x-step removed, x-tool added from tool-metadata.json
#   - configurations extracted as presets
#
# Reads from:
#   okapi-data/{version}/         (assembled okapi-native output)
#   schemagen/step-metadata.json  (neokapi tool classifications)
#
# Produces:
#   dist/plugin/
#   ├── manifest.json
#   ├── formats/{filterId}/
#   │   ├── schema.json
#   │   ├── doc.json
#   │   └── presets/
#   ├── tools/{stepId}/
#   │   ├── schema.json
#   │   └── doc.json
#   └── docs/
#       ├── metadata.json
#       └── concepts.json
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

# Verify prerequisites
if [ ! -d "$INPUT_DIR" ]; then
    echo "Error: $INPUT_DIR not found. Run 'make assemble V=${OKAPI_VERSION}' first." >&2
    exit 1
fi

if [ ! -f "$STEP_METADATA" ]; then
    echo "Error: $STEP_METADATA not found." >&2
    exit 1
fi

# Clean output
rm -rf "$OUTPUT_DIR"
mkdir -p "$OUTPUT_DIR/formats" "$OUTPUT_DIR/tools" "$OUTPUT_DIR/docs"

echo "Transforming okapi-data to neokapi plugin format..."

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

    # Transform filter schema -> format schema
    # - Rename x-filter to x-format
    # - Strip Java class names, parametersRaw, parametersLocation, serializationFormat
    # - Extract configurations as presets
    jq '
        # Rename x-filter -> x-format, strip okapi internals
        (if .["x-filter"] then
            .["x-format"] = (
                .["x-filter"] |
                del(.class) |
                del(.serializationFormat) |
                if .configurations then
                    .presets = [.configurations[] | {
                        id: .configId,
                        name: .name,
                        description: .description,
                        extensions: .extensions,
                        mimeType: .mimeType,
                        isDefault: .isDefault,
                        parameters: .parameters
                    } | del(.[] | select(. == null))]
                    | del(.configurations)
                else .
                end
            ) |
            del(.["x-filter"])
        else .
        end) |
        # Rewrite $id to neokapi namespace
        if .["$id"] then
            .["$id"] = (.["$id"] | gsub("okapiframework\\.org"; "neokapi.dev"))
        else .
        end
    ' "$schema_file" > "$OUTPUT_DIR/formats/${filter_id}/schema.json"

    # Extract presets into separate files
    if jq -e '.["x-format"].presets' "$OUTPUT_DIR/formats/${filter_id}/schema.json" > /dev/null 2>&1; then
        mkdir -p "$OUTPUT_DIR/formats/${filter_id}/presets"
        jq -c '.["x-format"].presets[]' "$OUTPUT_DIR/formats/${filter_id}/schema.json" | while read -r preset; do
            preset_id=$(echo "$preset" | jq -r '.id')
            echo "$preset" | jq '.' > "$OUTPUT_DIR/formats/${filter_id}/presets/${preset_id}.json"
        done
        # Remove inline presets from schema (they're in separate files now)
        jq '.["x-format"] |= del(.presets)' \
            "$OUTPUT_DIR/formats/${filter_id}/schema.json" > "$OUTPUT_DIR/formats/${filter_id}/schema.json.tmp"
        mv "$OUTPUT_DIR/formats/${filter_id}/schema.json.tmp" "$OUTPUT_DIR/formats/${filter_id}/schema.json"
    fi

    # Transform filter doc -> format doc
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
            name: ($format_schema["x-format"].id // $id),
            display_name: ($format_schema.title // $id),
            capabilities: ["read", "write"],
            mime_types: ($format_schema["x-format"].mimeTypes // []),
            extensions: ($format_schema["x-format"].extensions // []),
            schema: $schema,
            doc: (if input_filename then $doc else null end)
        } | if ($format_schema["x-format"].presets // [] | length) > 0
            then . + {presets_dir: $presets_dir}
            else .
          end
        | with_entries(select(.value != null and .value != []))
    ')
    # Fix: check if doc file actually exists
    if [ ! -f "$filter_dir/doc.json" ]; then
        cap=$(echo "$cap" | jq 'del(.doc)')
    fi
    # Fix: check if presets directory was created
    if [ ! -d "$OUTPUT_DIR/formats/${filter_id}/presets" ]; then
        cap=$(echo "$cap" | jq 'del(.presets_dir)')
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

    # Transform step schema -> tool schema
    # - Remove x-step (Java internals)
    # - Add x-tool from tool-metadata.json
    jq --arg sid "$step_id" --slurpfile meta "$STEP_METADATA" '
        del(.["x-step"]) |
        if $meta[0][$sid] then
            .["x-tool"] = $meta[0][$sid]
        else
            .["x-tool"] = {
                displayName: .title,
                category: "pipeline",
                inputs: ["block"]
            }
        end
    ' "$schema_file" > "$OUTPUT_DIR/tools/${step_id}/schema.json"

    # Transform step doc -> tool doc
    if [ -f "$step_dir/doc.json" ]; then
        jq '
            if .stepId then .toolId = .stepId | del(.stepId) else . end
        ' "$step_dir/doc.json" > "$OUTPUT_DIR/tools/${step_id}/doc.json"
    fi

    # Build capability entry for manifest using tool-metadata.json
    step_meta=$(jq --arg sid "$step_id" '.[$sid] // {}' "$STEP_METADATA")
    cap=$(jq -n --arg id "$step_id" \
        --arg schema "tools/${step_id}/schema.json" \
        --arg doc "tools/${step_id}/doc.json" \
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
    # Add doc path only if doc file exists
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
    # Vocabulary-map the metadata
    jq '
        if .aliases then
            .aliases = (.aliases | to_entries | map({
                key: .key,
                value: .value
            }) | from_entries)
        else .
        end
    ' docs/metadata.json > "$OUTPUT_DIR/docs/metadata.json"
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
