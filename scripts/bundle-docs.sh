#!/bin/bash
# Bundle parsed filter and step documentation into a single docs.json
# Usage: ./scripts/bundle-docs.sh [filter-docs-dir]
#
# Produces docs.json for inclusion in the plugin release archive.

set -e

DOCS_DIR="${1:-filter-docs}"
PARSED_DIR="$DOCS_DIR/parsed"
OUTPUT_FILE="$DOCS_DIR/docs.json"
WIKI_BASE_URL="https://okapiframework.org/wiki/index.php/"

if [ ! -d "$PARSED_DIR" ]; then
    echo "Error: $PARSED_DIR not found. Run 'make parse-filter-docs' first."
    exit 1
fi

echo "Bundling documentation..."

# Build the filters object from parsed filter docs
FILTERS="{}"
ALIASES="{}"
filter_count=0

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
    FILTERS=$(jq -s --arg id "$filter_id" '.[0] + {($id): .[1]}' <(echo "$FILTERS") "$json_file")
    ((filter_count++))
done

# Build the steps object from parsed step docs
STEPS="{}"
step_count=0

if [ -d "$PARSED_DIR/steps" ]; then
    for json_file in "$PARSED_DIR/steps"/*.json; do
        [ ! -f "$json_file" ] && continue
        step_id=$(basename "$json_file" .json)
        STEPS=$(jq -s --arg id "$step_id" '.[0] + {($id): .[1]}' <(echo "$STEPS") "$json_file")
        ((step_count++))
    done
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

# Assemble the final docs.json
jq -n \
    --arg generatedAt "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
    --arg wikiBaseUrl "$WIKI_BASE_URL" \
    --argjson filters "$FILTERS" \
    --argjson steps "$STEPS" \
    --argjson aliases "$ALIASES" \
    --argjson concepts "$CONCEPTS" \
    '{
        generatedAt: $generatedAt,
        wikiBaseUrl: $wikiBaseUrl,
        filters: $filters,
        steps: $steps,
        aliases: $aliases,
        concepts: $concepts
    }' > "$OUTPUT_FILE"

echo "Created $OUTPUT_FILE"
echo "  Filters: $filter_count"
echo "  Steps:   $step_count"
echo "  Aliases: $(echo "$ALIASES" | jq 'length')"
echo "  Size:    $(du -h "$OUTPUT_FILE" | cut -f1)"
