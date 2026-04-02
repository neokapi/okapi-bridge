#!/bin/bash
# Apply property suggestions from docs extraction to schema overrides.
# Usage: ./scripts/apply-property-suggestions.sh [filter-docs-dir]
#
# Reads property-suggestions.json from bundled docs and generates/updates
# override files in overrides/{filters,steps}/ with suggested titles and
# descriptions for properties that are missing them.
#
# The generated overrides are NOT auto-committed — review the diff before committing.

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"
cd "$ROOT_DIR"

DOCS_DIR="${1:-filter-docs}"
SUGGESTIONS_FILE="$DOCS_DIR/docs/property-suggestions.json"
FILTER_OVERRIDES_DIR="overrides/filters"
STEP_OVERRIDES_DIR="overrides/steps"

if [ ! -f "$SUGGESTIONS_FILE" ]; then
    echo "Error: $SUGGESTIONS_FILE not found. Run 'make bundle-docs' first."
    exit 1
fi

echo "Applying property suggestions to overrides..."
echo ""

applied=0
created=0
updated=0

# Process each component's suggestions
for component_id in $(jq -r 'keys[]' "$SUGGESTIONS_FILE"); do
    suggestions=$(jq --arg id "$component_id" '.[$id]' "$SUGGESTIONS_FILE")
    suggestion_count=$(echo "$suggestions" | jq 'length')

    if [ "$suggestion_count" -eq 0 ]; then
        continue
    fi

    # Determine if this is a filter or step
    if [[ "$component_id" == okf_* ]]; then
        override_file="$FILTER_OVERRIDES_DIR/${component_id}.overrides.json"
        component_type="filter"
    else
        override_file="$STEP_OVERRIDES_DIR/${component_id}.overrides.json"
        component_type="step"
    fi

    # Create or update override file
    if [ -f "$override_file" ]; then
        # Merge suggestions into existing fields
        existing_fields=$(jq '.fields // {}' "$override_file")
        merged_fields=$(echo "$existing_fields" | jq --argjson sugg "$suggestions" '
            # For each suggestion, add title and/or description — merge into existing entries
            # Existing values are preserved (suggestion fills gaps only)
            reduce ($sugg | to_entries[]) as $s (.;
                ($s.value | {title, description} | with_entries(select(.value != null))) as $hints |
                if $hints == {} then .
                else .[$s.key] = ($hints + (.[$s.key] // {}))
                end
            )
        ')

        if [ "$merged_fields" != "$existing_fields" ]; then
            jq --argjson fields "$merged_fields" '.fields = $fields' "$override_file" > "$override_file.tmp"
            mv "$override_file.tmp" "$override_file"
            echo "  ✓ $component_id: updated ($suggestion_count suggestions)"
            ((updated++))
        else
            echo "  = $component_id: no new fields to add"
        fi
    else
        # Create new override file with suggestions as fields
        fields=$(echo "$suggestions" | jq '
            to_entries | map(
                {key: .key, value: (
                    .value | to_entries | map(select(.key == "title" or .key == "description")) | from_entries
                )} | select(.value != {})
            ) | from_entries
        ')

        if [ "$(echo "$fields" | jq 'length')" -gt 0 ]; then
            jq -n --argjson fields "$fields" '{
                "$schema": "https://neokapi.dev/schemas/editorHints.schema.json",
                "fields": $fields
            }' > "$override_file"
            echo "  + $component_id: created ($suggestion_count suggestions)"
            ((created++))
        fi
    fi

    applied=$((applied + suggestion_count))
done

echo ""
echo "Done!"
echo "  Applied: $applied suggestions"
echo "  Created: $created new override files"
echo "  Updated: $updated existing override files"
echo ""
echo "Review changes with: git diff overrides/"
