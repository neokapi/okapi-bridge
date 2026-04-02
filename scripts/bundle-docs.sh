#!/bin/bash
# Bundle parsed filter and step documentation for plugin distribution.
# Usage: ./scripts/bundle-docs.sh [filter-docs-dir]
#
# Produces:
#   docs/filters/<id>.json   — one file per filter
#   docs/steps/<id>.json     — one file per step
#   docs/metadata.json       — aliases, generation timestamp, wiki URL
#   docs/concepts.json       — shared documentation concepts

set -e

DOCS_DIR="${1:-filter-docs}"
PARSED_DIR="$DOCS_DIR/parsed"
OUTPUT_DIR="$DOCS_DIR/docs"
WIKI_BASE_URL="https://okapiframework.org/wiki/index.php/"

if [ ! -d "$PARSED_DIR" ]; then
    echo "Error: $PARSED_DIR not found. Run 'make parse-filter-docs' first."
    exit 1
fi

echo "Bundling documentation..."

# --- Per-file output (docs/ directory) ---

rm -rf "$OUTPUT_DIR"
mkdir -p "$OUTPUT_DIR/filters" "$OUTPUT_DIR/steps"

ALIASES="{}"
SUGGESTIONS="{}"
filter_count=0
md_count=0

for json_file in "$PARSED_DIR"/okf_*.json; do
    [ ! -f "$json_file" ] && continue

    # Skip symlinks — they become aliases
    if [ -L "$json_file" ]; then
        alias_id=$(basename "$json_file" .json)
        target=$(readlink "$json_file" | sed 's/\.json$//')
        ALIASES=$(echo "$ALIASES" | jq --arg alias "$alias_id" --arg target "$target" '. + {($alias): $target}')
        continue
    fi

    filter_id=$(basename "$json_file" .json)
    cp "$json_file" "$OUTPUT_DIR/filters/${filter_id}.json"
    ((filter_count++))

    # Extract fullDoc as markdown file (unescape literal \n from LLM output)
    full_doc=$(jq -r '.fullDoc // empty' "$json_file")
    if [ -n "$full_doc" ]; then
        printf '%b\n' "$full_doc" > "$OUTPUT_DIR/filters/${filter_id}.md"
        ((md_count++))
    fi

    # Collect property suggestions
    suggestions=$(jq -c '.propertySuggestions // empty' "$json_file")
    if [ -n "$suggestions" ] && [ "$suggestions" != "null" ] && [ "$suggestions" != "{}" ]; then
        SUGGESTIONS=$(echo "$SUGGESTIONS" | jq --arg id "$filter_id" --argjson s "$suggestions" '.[$id] = $s')
    fi
done

step_count=0

if [ -d "$PARSED_DIR/steps" ]; then
    for json_file in "$PARSED_DIR/steps"/*.json; do
        [ ! -f "$json_file" ] && continue
        step_id=$(basename "$json_file" .json)
        cp "$json_file" "$OUTPUT_DIR/steps/${step_id}.json"
        ((step_count++))

        # Extract fullDoc as markdown file (unescape literal \n from LLM output)
        full_doc=$(jq -r '.fullDoc // empty' "$json_file")
        if [ -n "$full_doc" ]; then
            printf '%b\n' "$full_doc" > "$OUTPUT_DIR/steps/${step_id}.md"
            ((md_count++))
        fi

        # Collect property suggestions
        suggestions=$(jq -c '.propertySuggestions // empty' "$json_file")
        if [ -n "$suggestions" ] && [ "$suggestions" != "null" ] && [ "$suggestions" != "{}" ]; then
            SUGGESTIONS=$(echo "$SUGGESTIONS" | jq --arg id "$step_id" --argjson s "$suggestions" '.[$id] = $s')
        fi
    done
fi

# Write aggregated property suggestions
if [ "$(echo "$SUGGESTIONS" | jq 'length')" -gt 0 ]; then
    echo "$SUGGESTIONS" | jq '.' > "$OUTPUT_DIR/property-suggestions.json"
    suggestion_count=$(echo "$SUGGESTIONS" | jq '[.[] | length] | add // 0')
    echo "  Property suggestions: $suggestion_count across $(echo "$SUGGESTIONS" | jq 'length') components"
fi

# Build the concepts section (hand-curated cross-cutting documentation)
CONCEPTS=$(cat <<'CONCEPTS_EOF'
{
  "ruleTypes": {
    "wikiRef": "Understanding_Filter_Configurations",
    "description": "Rule types control how elements and attributes are handled during extraction. Each element or attribute rule must have a ruleTypes array.",
    "elementRuleTypes": {
      "INLINE": "Inline element — content flows within surrounding text (e.g. <b>, <span>, <a>)",
      "TEXTUNIT": "Text unit — extracted as a translatable segment with skeleton before/after (e.g. <p>, <h1>, <li>)",
      "EXCLUDE": "Excluded — element and all children are skipped during extraction (e.g. <script>, <style>)",
      "INCLUDE": "Included — exception to EXCLUDE, re-enables extraction inside an excluded block",
      "GROUP": "Group element — structural container, not translatable itself (e.g. <table>, <ul>, <div>)",
      "ATTRIBUTES_ONLY": "Only attributes are translatable/localizable, not the element's text content (e.g. <meta>)",
      "PRESERVE_WHITESPACE": "Preserve whitespace inside this element (e.g. <pre>, <code>)",
      "SCRIPT": "Script element — embedded client-side code (e.g. <script>)",
      "SERVER": "Server element — embedded server-side content (e.g. JSP, PHP, Mason tags)"
    },
    "attributeRuleTypes": {
      "ATTRIBUTE_TRANS": "Translatable — attribute content is extracted for translation (e.g. alt, title)",
      "ATTRIBUTE_WRITABLE": "Writable localizable — attribute is locale-specific and editable (e.g. href, src)",
      "ATTRIBUTE_READONLY": "Read-only localizable — attribute is locale-specific but not user-editable",
      "ATTRIBUTE_ID": "ID — attribute value is used as the segment identifier",
      "ATTRIBUTE_PRESERVE_WHITESPACE": "Preserve whitespace — attribute controls whitespace preservation state (e.g. xml:space)"
    }
  },
  "conditions": {
    "wikiRef": "Understanding_Filter_Configurations",
    "description": "Conditions are triples [attributeName, operator, value] that make extraction rules conditional on an attribute's value.",
    "operators": {
      "EQUALS": "Case-insensitive string equality. With array values, matches if attribute equals any value (OR logic).",
      "NOT_EQUALS": "Case-insensitive string inequality. With array values, must not equal any of the values (AND logic).",
      "MATCHES": "Java regex match. Must match the entire attribute value."
    },
    "examples": [
      {"description": "Extract only when translate=yes", "value": ["translate", "EQUALS", "yes"]},
      {"description": "Skip file/hidden input types", "value": ["type", "NOT_EQUALS", ["file", "hidden"]]},
      {"description": "Match data- attributes via regex", "value": ["data-i18n", "MATCHES", ".*"]}
    ]
  },
  "codeFinderRules": {
    "wikiRef": "Inline_Codes_Simplifier_Step",
    "description": "Regex patterns for detecting inline codes (placeholders, tags, format specifiers) within translatable text. Each pattern is a Java regex that matches content to be protected as an inline code."
  },
  "simplifierRules": {
    "wikiRef": "Inline_Codes_Simplifier_Step",
    "description": "Rules for simplifying inline code representation. Uses a custom grammar: 'if FIELD OPERATOR VALUE [and|or ...]; ...'",
    "grammar": {
      "fields": ["DATA", "OUTER_DATA", "ORIGINAL_ID", "TYPE", "TAG_TYPE"],
      "flags": ["ADDABLE", "DELETABLE", "CLONEABLE"],
      "operators": ["=", "!=", "~", "!~"],
      "tagTypes": ["OPENING", "CLOSING", "STANDALONE"]
    }
  }
}
CONCEPTS_EOF
)

# Write concepts.json
echo "$CONCEPTS" | jq '.' > "$OUTPUT_DIR/concepts.json"

# Write metadata.json (aliases, generation info, wiki URL)
GENERATED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
jq -n \
    --arg generatedAt "$GENERATED_AT" \
    --arg wikiBaseUrl "$WIKI_BASE_URL" \
    --argjson aliases "$ALIASES" \
    '{
        generatedAt: $generatedAt,
        wikiBaseUrl: $wikiBaseUrl,
        aliases: $aliases
    }' > "$OUTPUT_DIR/metadata.json"

echo "Created $OUTPUT_DIR/"
echo "  Filters: $filter_count (docs/filters/)"
echo "  Steps:   $step_count (docs/steps/)"
echo "  Markdown: $md_count (.md files)"
echo "  Aliases: $(echo "$ALIASES" | jq 'length')"
