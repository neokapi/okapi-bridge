#!/bin/bash
# Extract parameter labels from Okapi SWT Java source code.
# Usage: ./scripts/extract-java-labels.sh <okapi-source> [output-file]
#
# Processes one component at a time — sends only the relevant Java UI files
# for each filter/step that has missing titles. Uses Claude CLI for the
# control→parameter mapping.

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(dirname "$SCRIPT_DIR")"
cd "$REPO_ROOT"

OKAPI_SOURCE="${1:?Usage: $0 <okapi-source> [output-file]}"
OUTPUT="${2:-schemagen/java-labels.json}"
MODEL="${MODEL:-sonnet}"

if [ ! -d "$OKAPI_SOURCE/okapi-ui" ]; then
    echo "Error: $OKAPI_SOURCE/okapi-ui not found" >&2
    exit 1
fi

# Map filter/step IDs to their Okapi UI directory names
find_ui_files() {
    local component_id="$1"
    local files=""

    # Filter UI: okapi-ui/swt/filters/{name}-ui/
    # Strip okf_ prefix and try variations
    local filter_name="${component_id#okf_}"

    # Try direct match and common variations
    for dir in \
        "$OKAPI_SOURCE/okapi-ui/swt/filters/${filter_name}-ui" \
        "$OKAPI_SOURCE/okapi-ui/swt/filters/${filter_name}s-ui" \
        "$OKAPI_SOURCE/okapi-ui/swt/filters/table-ui"; do
        if [ -d "$dir/src" ]; then
            files="$files $(find "$dir/src" -name "*.java" -exec grep -l '\.setText(' {} \; 2>/dev/null)"
        fi
    done

    # Step UI: okapi-ui/swt/steps/{name}-ui/
    for dir in \
        "$OKAPI_SOURCE/okapi-ui/swt/steps/${component_id}-ui" \
        "$OKAPI_SOURCE/okapi-ui/swt/steps/${filter_name}-ui"; do
        if [ -d "$dir/src" ]; then
            files="$files $(find "$dir/src" -name "*.java" -exec grep -l '\.setText(' {} \; 2>/dev/null)"
        fi
    done

    # Also check the filter/step Parameters class itself for IEditorDescriptionProvider
    for dir in \
        "$OKAPI_SOURCE/okapi/filters/${filter_name}/src" \
        "$OKAPI_SOURCE/okapi/steps/${filter_name}/src"; do
        if [ -d "$dir" ]; then
            files="$files $(find "$dir" -name "Parameters*.java" 2>/dev/null)"
        fi
    done

    echo "$files" | tr ' ' '\n' | sort -u | grep -v '^$'
}

# Collect missing properties per component
MISSING_FILE="/tmp/missing-labels.json"
python3 -c "
import json, glob, os
result = {}
for pattern in ['schemas/filters/composite/*.schema.json', 'schemas/steps/composite/*.schema.json']:
    for f in sorted(glob.glob(pattern)):
        cid = os.path.basename(f).rsplit('.v', 1)[0]
        with open(f) as fh:
            s = json.load(fh)
        missing = []
        for pname, pval in s.get('properties', {}).items():
            if not isinstance(pval, dict): continue
            if not pval.get('title'):
                missing.append(pname)
        if missing:
            result[cid] = missing
json.dump(result, open('$MISSING_FILE', 'w'), indent=2)
print(f'{sum(len(v) for v in result.values())} properties missing titles across {len(result)} components')
"

echo ""
echo "Extracting labels from Java source (model: $MODEL)..."
echo ""

# Process each component
RESULT="{}"
success=0
skipped=0
failed=0

for component_id in $(jq -r 'keys[]' "$MISSING_FILE"); do
    missing_props=$(jq -c --arg id "$component_id" '.[$id]' "$MISSING_FILE")

    # Find relevant Java UI files
    java_files=$(find_ui_files "$component_id")

    if [ -z "$java_files" ]; then
        echo "  ⏭ $component_id (no UI source found)"
        ((skipped++))
        continue
    fi

    # Combine the Java source for this component
    java_source=""
    for jf in $java_files; do
        [ -f "$jf" ] || continue
        rel="${jf#$OKAPI_SOURCE/}"
        java_source="$java_source
--- $rel ---
$(cat "$jf")
"
    done

    source_size=${#java_source}
    if [ "$source_size" -lt 100 ]; then
        echo "  ⏭ $component_id (source too small)"
        ((skipped++))
        continue
    fi

    echo -n "  $component_id ($source_size bytes)... "

    prompt="Extract parameter labels from this Java SWT UI code.

Missing parameters for component '$component_id': $missing_props

For each parameter, find the .setText(\"...\") label by tracing:
1. A control variable (chkFoo, edBar, rdBaz, stFoo) gets .setText(\"Label\")
2. That control maps to params.paramName in setData()/saveData()

Output ONLY a JSON object:
{\"paramName\": {\"title\": \"Label text\"}, ...}

Rules:
- Only include parameters from the missing list above
- Use the exact .setText() label, removing only trailing colons
- Omit parameters with no clear label in the code

JAVA SOURCE:
$java_source"

    if raw_result=$(echo "$prompt" | claude --print --dangerously-skip-permissions \
        --model "$MODEL" \
        --output-format json \
        2>/dev/null); then

        # Extract JSON from result (may be wrapped in markdown code fences)
        component_labels=$(echo "$raw_result" | jq -r '.result // empty' | python3 -c "
import sys, json, re
text = sys.stdin.read()
# Strip markdown code fences if present
text = re.sub(r'^\`\`\`(?:json)?\s*\n?', '', text.strip())
text = re.sub(r'\n?\`\`\`\s*$', '', text.strip())
# Find outermost JSON object
depth = 0; start = -1
for i, c in enumerate(text):
    if c == '{':
        if depth == 0: start = i
        depth += 1
    elif c == '}':
        depth -= 1
        if depth == 0 and start >= 0:
            try:
                obj = json.loads(text[start:i+1])
                json.dump(obj, sys.stdout)
                sys.exit(0)
            except: pass
" 2>/dev/null)

        if [ -n "$component_labels" ] && [ "$component_labels" != "{}" ]; then
            count=$(echo "$component_labels" | jq 'length')
            RESULT=$(echo "$RESULT" | jq --arg id "$component_id" --argjson labels "$component_labels" '.[$id] = $labels')
            echo "✓ ($count labels)"
            ((success++))
        else
            echo "✗ (no labels extracted)"
            ((failed++))
        fi
    else
        echo "✗ (Claude error)"
        ((failed++))
    fi

    sleep 0.5
done

echo ""
echo "Extraction complete: $success succeeded, $skipped skipped (no UI), $failed failed"

if [ "$success" -gt 0 ]; then
    echo "$RESULT" | jq '.' > "$OUTPUT"
    total=$(echo "$RESULT" | jq '[.[] | length] | add // 0')
    echo "Output: $OUTPUT ($total labels)"
fi
