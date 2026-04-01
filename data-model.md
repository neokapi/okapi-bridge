# Okapi Bridge Data Model Reference

This document describes the complete data model for schemas, documentation, and metadata produced by okapi-bridge. It is intended as a reference for implementing UIs, CLI tools, and AI agents that consume this data.

## Overview

The bridge uses a two-stage pipeline to produce artifacts:

1. **Okapi-native extraction** (`okapi-data/{version}/`) — All data in pure Okapi vocabulary (filters, steps, configurations). This is the community reference artifact.
2. **neokapi plugin transformation** (`dist/plugin/`) — Data transformed to neokapi vocabulary (formats, tools, presets). This is what neokapi consumes.

### Okapi-Native Output (Community Reference)

```
okapi-data/{version}/
├── meta.json                        # Version metadata
├── filters/
│   └── okf_html/
│       ├── schema.json              # Filter parameter schema (x-filter, x-editor)
│       └── doc.json                 # Curated documentation from wiki
├── steps/
│   └── search-and-replace/
│       ├── schema.json              # Step parameter schema (x-step only)
│       └── doc.json                 # Curated documentation
├── concepts.json                    # Cross-cutting concept documentation
└── versions.json                    # Version tracking with hashes
```

### neokapi Plugin Output (What Gets Shipped)

```
dist/plugin/
├── manifest.json                    # Plugin capabilities with resource paths
├── neokapi-bridge-jar-with-dependencies.jar
├── formats/
│   └── okf_html/
│       ├── schema.json              # Format schema (x-format, no x-filter)
│       ├── doc.json                 # Documentation (neokapi vocabulary)
│       └── presets/                 # Extracted preset configurations
│           ├── okf_html.json
│           └── okf_html-wellFormed.json
├── tools/
│   └── search-and-replace/
│       ├── schema.json              # Tool schema (x-tool, no x-step)
│       └── doc.json                 # Documentation (neokapi vocabulary)
└── docs/
    ├── metadata.json
    └── concepts.json
```

### Vocabulary Mapping

| Okapi (extraction) | neokapi (plugin) |
|---------------------|------------------|
| filter              | format           |
| step                | tool             |
| configuration       | preset           |
| x-filter            | x-format         |
| x-step              | x-tool           |
| filterId            | formatId         |
| stepId              | toolId           |

The sections below describe the **okapi-native** data model. The plugin output uses the same structure with vocabulary mapped per the table above, plus presets extracted to separate files.

---

## 1. Filter Schemas

**Location:** `schemas/{filterId}.schema.json`

Filter schemas are JSON Schema (draft-07) documents that define the configuration parameters for an Okapi file format filter. They combine auto-generated structure from Okapi introspection with hand-curated UI overrides.

### Root Structure

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "$id": "https://neokapi.github.io/schemas/filters/okf_html.v6.schema.json",
  "version": 6,
  "title": "HTML/XHTML Filter",
  "description": "Configuration for the Okapi HTML/XHTML Filter",
  "type": "object",
  "x-filter": { ... },
  "x-kind": "OkfHtmlFilterConfig",
  "x-apiVersion": "v6",
  "x-baseVersion": 12,
  "x-introducedInOkapi": "1.48.0",
  "x-baseHash": "abc123...",
  "x-compositeHash": "def456...",
  "properties": { ... },
  "$defs": { ... },
  "additionalProperties": false
}
```

| Field | Type | Description |
|-------|------|-------------|
| `version` | integer | Composite schema version. Increments when base or override content changes. |
| `x-filter` | object | Filter metadata (see below). |
| `x-kind` | string | CamelCase identifier for this filter configuration variant. |
| `x-apiVersion` | string | API version string (e.g. `"v6"`). Used for consumer compatibility. |
| `x-baseVersion` | integer | Version of the base schema before override merging. |
| `x-introducedInOkapi` | string | Okapi version where this schema variant was first seen. |
| `x-baseHash` | string | Content hash of the base schema (first 12 chars of SHA-1). |
| `x-compositeHash` | string | Content hash of the merged composite schema. |
| `properties` | object | Parameter definitions (see [Properties](#properties)). |
| `$defs` | object | Reusable type definitions (see [$defs](#defs)). |

### x-filter

Metadata about the filter itself, including its configurations (presets).

```json
{
  "id": "okf_html",
  "class": "net.sf.okapi.filters.html.HtmlFilter",
  "extensions": [".html", ".htm", ".xhtml"],
  "mimeTypes": ["text/html"],
  "serializationFormat": "yaml",
  "configurations": [
    {
      "configId": "okf_html",
      "name": "HTML (Well-Formed)",
      "description": "HTML and XHTML documents (well-formed)",
      "mimeType": "text/html",
      "extensions": ".html;.htm;",
      "parametersLocation": "wellformedConfiguration.yml",
      "parametersRaw": "assumeWellformed: true\npreserve_whitespace: ...",
      "parameters": { "assumeWellformed": true, ... },
      "isDefault": true,
      "filterClass": "net.sf.okapi.filters.html.HtmlFilter"
    }
  ]
}
```

| Field | Type | Description |
|-------|------|-------------|
| `id` | string | Filter identifier (e.g. `okf_html`, `okf_json`). Always prefixed with `okf_`. |
| `class` | string | Fully-qualified Java class name of the Okapi filter. |
| `extensions` | string[] | File extensions this filter handles (e.g. `[".html", ".htm"]`). |
| `mimeTypes` | string[] | MIME types this filter handles. |
| `serializationFormat` | string | How filter parameters are serialized. One of: `"stringParameters"` (#v1 key=value), `"yaml"` (AbstractMarkupParameters), `"xml-its"` (XML ITS rules with DOM). |
| `configurations` | array | Available preset configurations (see below). |

**Configuration object:**

| Field | Type | Description |
|-------|------|-------------|
| `configId` | string | Configuration identifier (e.g. `okf_html`, `okf_xml-resx`). |
| `name` | string | Human-readable name. |
| `description` | string | Description of what this configuration is for. |
| `parametersLocation` | string | Filename of the bundled parameter file (e.g. `wellformedConfiguration.yml`). |
| `parametersRaw` | string | Raw content of the parameter file (YAML, #v1, or XML). |
| `parameters` | object | Parsed parameter values as a JSON object. `null` for opaque formats (XML ITS). |
| `isDefault` | boolean | Whether this is the default configuration. |

### Properties

Each property in the `properties` object describes a configurable parameter. Properties use standard JSON Schema keywords plus an `x-editor` extension for UI metadata.

**Standard JSON Schema fields used:**

| Field | Type | Description |
|-------|------|-------------|
| `type` | string | `"string"`, `"boolean"`, `"integer"`, `"number"`, `"object"`, `"array"` |
| `default` | any | Default value. |
| `title` | string | Display name from Okapi ParametersDescription. |
| `description` | string | Description from Okapi ParametersDescription. |
| `enum` | array | Allowed values (from ListSelectionPart). |
| `minimum` / `maximum` | number | Range constraints (from SpinInputPart). |
| `deprecated` | boolean | Whether this parameter is deprecated. |

**Extension fields:**

| Field | Type | Description |
|-------|------|-------------|
| `x-editor` | object | Structured UI editor metadata (see [x-editor](#x-editor)). |
| `x-flattenPath` | string | Original Okapi parameter name, when the schema uses a cleaner name. Used by ParameterFlattener at runtime to map hierarchical JSON to flat Okapi params. |
| `x-okapiFormat` | string | Identifies complex Okapi types (e.g. `"inlineCodeFinder"`). |
| `x-placeholder` | string | Placeholder text for input fields (from overrides). |
| `x-presets` | object | Preset values for this parameter (from overrides). |
| `x-order` | number | Display order hint (from overrides). |
| `x-showIf` | object | Conditional visibility rule (from overrides). |

**Grouped properties:** Some filters use hierarchical property grouping. For example, the HTML filter groups `assumeWellformed` and `preserveWhitespace` under a `parser` object. Each property in the group carries `x-flattenPath` to map back to the flat Okapi parameter name.

```json
{
  "parser": {
    "type": "object",
    "description": "Parser settings",
    "properties": {
      "assumeWellformed": {
        "type": "boolean",
        "default": false,
        "x-flattenPath": "assumeWellformed"
      }
    }
  }
}
```

**Shared groups via $ref:** Common parameter groups (inline codes, whitespace) are defined in `$defs` and referenced via `$ref`:

```json
{
  "inlineCodes": { "$ref": "#/$defs/inlineCodes" }
}
```

### x-editor

Structured UI metadata conforming to `schemagen/x-editor.schema.json`. Mirrors the Okapi `EditorDescription` / `AbstractPart` class hierarchy.

```json
{
  "x-editor": {
    "widget": "path",
    "enabledBy": {
      "parameter": "segment",
      "enabledWhenSelected": true
    },
    "layout": {
      "withLabel": false,
      "vertical": true
    },
    "path": {
      "browseTitle": "SRX Path",
      "forSaveAs": false,
      "allowEmpty": false,
      "filters": [
        { "name": "SRX Documents (*.srx)", "extensions": "*.srx" },
        { "name": "All Files (*.*)", "extensions": "*.*" }
      ]
    }
  }
}
```

**Top-level properties (all widgets):**

| Field | Type | Description |
|-------|------|-------------|
| `widget` | string | Widget type (discriminator). Required. See values below. |
| `enabledBy` | object | Master/slave dependency. `parameter` names the controlling parameter; `enabledWhenSelected` indicates the enabled state. From `AbstractPart.getMasterPart()`. |
| `layout` | object | Layout hints. `withLabel` (default true): show label. `vertical` (default false): label above input vs beside. From `AbstractPart.isVertical()` / `isWithLabel()`. |

**Widget types and their specific properties:**

| Widget | Okapi Source | Specific Properties |
|--------|-------------|---------------------|
| `checkbox` | `CheckboxPart` | (none) |
| `text` | `TextInputPart` | `text.password`, `text.allowEmpty`, `text.height` |
| `spin` | `SpinInputPart` | Uses standard `minimum`/`maximum` on the property |
| `dropdown` | `ListSelectionPart` (DROPDOWN) | Uses standard `enum` + `x-enumLabels` on the property |
| `select` | `ListSelectionPart` (LIST) | Uses standard `enum` + `x-enumLabels` on the property |
| `path` | `PathInputPart` | `path.browseTitle`, `path.forSaveAs`, `path.allowEmpty`, `path.filters[]` |
| `folder` | `FolderInputPart` | `folder.browseTitle` |
| `codeFinder` | `CodeFinderPart` | (none — uses codeFinderRules object schema) |
| `checkList` | `CheckListPart` | `checkList.entries[]` with `name`, `title`, `description` |

**Widget-specific sub-objects:**

`text` (for `widget: "text"`):

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `password` | boolean | false | Mask input as password field. |
| `allowEmpty` | boolean | false | Accept empty/blank values. |
| `height` | integer | 1 | Rows for multiline text area. Omit or 1 for single-line. |

`path` (for `widget: "path"`):

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `browseTitle` | string | | Title for the file browse dialog. |
| `forSaveAs` | boolean | false | Show Save-As dialog instead of Open. |
| `allowEmpty` | boolean | false | Accept empty path values. |
| `filters` | array | | File type filters: `[{name: "Label", extensions: "*.ext"}]` |

`folder` (for `widget: "folder"`):

| Field | Type | Description |
|-------|------|-------------|
| `browseTitle` | string | Title for the folder browse dialog. |

`checkList` (for `widget: "checkList"`):

| Field | Type | Description |
|-------|------|-------------|
| `entries` | array | Checkbox entries: `[{name: "paramName", title: "Label", description: "Help text"}]` |

### $defs

Reusable type definitions referenced via `$ref` from properties. These appear in filter schemas that use YAML-based configurations (HTML, XMLStream, etc.).

#### conditionTuple

A 3-element tuple representing a conditional rule: `[attributeName, operator, value]`.

```json
{
  "type": "array",
  "minItems": 3,
  "maxItems": 3,
  "prefixItems": [
    { "type": "string", "description": "Attribute name to test" },
    {
      "type": "string",
      "enum": ["EQUALS", "NOT_EQUALS", "MATCHES"],
      "x-enumDescriptions": {
        "EQUALS": "Case-insensitive string equality",
        "NOT_EQUALS": "Case-insensitive string inequality",
        "MATCHES": "Java regex match (must match entire attribute value)"
      }
    },
    {
      "description": "Value to compare",
      "oneOf": [
        { "type": "string" },
        { "type": "array", "items": { "type": "string" } }
      ]
    }
  ]
}
```

When the value is an array: OR logic for `EQUALS`/`MATCHES`, AND logic for `NOT_EQUALS`.

**Examples:**
```json
["translate", "EQUALS", "yes"]
["type", "NOT_EQUALS", ["file", "hidden", "image"]]
["data-i18n", "MATCHES", ".*"]
```

#### elementRule

Extraction rule for an HTML/XML element. Used as `additionalProperties` in the `elements` map.

| Property | Type | Required | Description |
|----------|------|----------|-------------|
| `ruleTypes` | string[] | Yes | Array of rule type enums (see below). |
| `elementType` | string | No | Semantic type hint: `bold`, `italic`, `link`, `image`, `paragraph`, `underlined`. |
| `translatableAttributes` | string[] or object | No | Attributes to extract as translatable. Simple array or conditional map. |
| `writableLocalizableAttributes` | string[] or object | No | Attributes to extract as writable localizable (e.g. URLs). |
| `readOnlyLocalizableAttributes` | string[] or object | No | Attributes to extract as read-only localizable. |
| `idAttributes` | string[] | No | Attributes whose values become segment IDs. |
| `conditions` | conditionTuple | No | Condition that must match for this rule to apply. |

**Element rule types** (with `x-enumDescriptions`):

| Value | Description |
|-------|-------------|
| `INLINE` | Inline element — content flows within surrounding text (e.g. `<b>`, `<span>`, `<a>`). |
| `INLINE_EXCLUDED` | Inline element excluded by a conditional rule. |
| `INLINE_INCLUDED` | Inline element included by a conditional rule (exception to EXCLUDE). |
| `TEXTUNIT` | Text unit — extracted as a translatable segment with skeleton before/after. |
| `EXCLUDE` | Excluded — element and all children are skipped during extraction. |
| `INCLUDE` | Included — exception to an EXCLUDE rule, re-enables extraction inside excluded block. |
| `GROUP` | Group element — structural container (e.g. `<table>`, `<ul>`, `<div>`). |
| `ATTRIBUTES_ONLY` | Only attributes are translatable/localizable, not the element's text content. |
| `PRESERVE_WHITESPACE` | Preserve whitespace inside this element (e.g. `<pre>`, `<code>`). |
| `SCRIPT` | Script element — embedded client-side code (e.g. `<script>`). |
| `SERVER` | Server element — embedded server-side content (e.g. JSP, PHP, Mason tags). |

**Attribute extraction formats:**

Simple form — list of attribute names:
```json
{ "translatableAttributes": ["alt", "title", "placeholder"] }
```

Conditional form — map of attribute name to condition(s):
```json
{
  "translatableAttributes": {
    "content": [
      ["http-equiv", "EQUALS", "keywords"],
      ["name", "EQUALS", ["keywords", "description"]]
    ]
  }
}
```

#### attributeRule

Global extraction rule for an HTML/XML attribute. Used as `additionalProperties` in the `attributes` map.

| Property | Type | Required | Description |
|----------|------|----------|-------------|
| `ruleTypes` | string[] | Yes | Array of attribute rule type enums (see below). |
| `allElementsExcept` | string[] | No | Apply to all elements except these. Mutually exclusive with `onlyTheseElements`. |
| `onlyTheseElements` | string[] | No | Apply only to these elements. |
| `conditions` | conditionTuple | No | Condition for this rule. |
| `preserve` | conditionTuple | No | Condition for `ATTRIBUTE_PRESERVE_WHITESPACE` activation. |
| `default` | conditionTuple | No | Condition for restoring default whitespace handling. |

**Attribute rule types:**

| Value | Description |
|-------|-------------|
| `ATTRIBUTE_TRANS` | Translatable — attribute content is extracted for translation. |
| `ATTRIBUTE_WRITABLE` | Writable localizable — locale-specific and editable (e.g. URLs, paths). |
| `ATTRIBUTE_READONLY` | Read-only localizable — locale-specific but not user-editable. |
| `ATTRIBUTE_ID` | ID — attribute value is used as the segment identifier. |
| `ATTRIBUTE_PRESERVE_WHITESPACE` | Controls whitespace preservation state (e.g. `xml:space`). |

#### conditionalAttributeValue

Used in element rule attribute maps. Can be:
- `null` — extract unconditionally
- A single `conditionTuple`
- An array of `conditionTuple` (OR logic — extract if any condition matches)

#### inlineCodes

Shared definition for inline code detection settings.

| Property | Type | x-flattenPath | Description |
|----------|------|--------------|-------------|
| `enabled` | boolean | `useCodeFinder` | Enable pattern-based inline code detection. |
| `rules` | codeFinderRules | `codeFinderRules` | Regex patterns configuration. |
| `mergeAdjacent` | boolean | `mergeAdjacentCodes` | Merge consecutive inline codes into one. |
| `moveBoundaryCodes` | boolean | `moveLeadingAndTrailingCodesToSkeleton` | Move codes at segment boundaries to skeleton. |
| `simplifierRules` | string | `simplifierRules` | Code simplifier rules (custom grammar). |

#### codeFinderRules

Regex patterns for detecting inline codes within translatable text.

```json
{
  "rules": [
    { "pattern": "<[^>]+>" },
    { "pattern": "\\{\\d+\\}" }
  ],
  "sample": "<b>Hello</b> {0}",
  "useAllRulesWhenTesting": true
}
```

| Property | Type | Description |
|----------|------|-------------|
| `rules` | array | Array of `{pattern: "regex"}` objects. Each pattern is a Java regex. |
| `sample` | string | Sample text for testing patterns in the UI. |
| `useAllRulesWhenTesting` | boolean | Test all rules combined (true) or individually (false). |

---

## 2. Step Schemas

**Location:** `schemas/steps/{stepId}.schema.json`

Step schemas define the parameters for Okapi pipeline processing steps (tools). They share the same property and `x-editor` conventions as filter schemas but add step-specific metadata.

### Root Structure

```json
{
  "$id": "batch-translation",
  "title": "Batch Translation",
  "type": "object",
  "x-step": { ... },
  "x-component": { ... },
  "properties": { ... }
}
```

### x-step

Internal Okapi step metadata extracted via reflection.

```json
{
  "class": "net.sf.okapi.steps.batchtranslation.BatchTranslationStep",
  "parameterMappings": ["SOURCE_LOCALE", "TARGET_LOCALE", "INPUT_ROOT_DIRECTORY"],
  "eventHandlers": ["handleRawDocument", "handleStartBatch", "handleEndBatchItem"],
  "interfaces": [],
  "inputType": "filter-events",
  "outputType": "filter-events"
}
```

| Field | Type | Description |
|-------|------|-------------|
| `class` | string | Fully-qualified Java class name of the step. |
| `parameterMappings` | string[] | `@StepParameterMapping` annotations — pipeline parameters this step consumes (e.g. `SOURCE_LOCALE`, `TARGET_LOCALE`, `INPUT_RAWDOC`). |
| `eventHandlers` | string[] | Overridden `handle*` methods — determines which Okapi events this step processes. |
| `interfaces` | string[] | Marker interfaces implemented (e.g. `ILoadsResources`). |
| `inputType` | string | `"filter-events"` or `"raw-document"`. |
| `outputType` | string | `"filter-events"` or `"file"`. |

### x-component

Neokapi integration metadata — how this step is presented in the tool system.

```json
{
  "id": "batch-translation",
  "type": "step",
  "displayName": "Batch Translation",
  "description": "Creates translations from an external program...",
  "category": "translate",
  "inputs": ["block"],
  "outputs": ["data"],
  "tags": ["batch", "translation"],
  "requires": ["target-language", "source-language"]
}
```

| Field | Type | Description |
|-------|------|-------------|
| `id` | string | Step identifier in kebab-case. |
| `type` | string | Always `"step"`. |
| `displayName` | string | Human-readable name. |
| `description` | string | What this step does, its expected input/output. |
| `category` | string | One of: `translate`, `validate`, `transform`, `convert`, `enrich`, `pipeline`. |
| `inputs` | string[] | Input part types: `block` (text units), `data` (document parts), `media` (raw documents). |
| `outputs` | string[] | Output part types. |
| `tags` | string[] | Freeform tags: `batch`, `translation`, `analysis`, `quality`, `text-processing`, `regex`, `configurable`. |
| `requires` | string[] | Runtime requirements: `target-language`, `source-language`. |

### Step Properties

Step properties use the same JSON Schema conventions as filter properties, including `x-editor` for UI metadata. Example:

```json
{
  "srxPath": {
    "type": "string",
    "default": "",
    "description": "Full path of the segmentation rules file to use",
    "x-editor": {
      "widget": "path",
      "enabledBy": { "parameter": "segment", "enabledWhenSelected": true },
      "layout": { "withLabel": false, "vertical": true },
      "path": {
        "browseTitle": "SRX Path",
        "filters": [
          { "name": "SRX Documents (*.srx)", "extensions": "*.srx" },
          { "name": "All Files (*.*)", "extensions": "*.*" }
        ]
      }
    }
  }
}
```

---

## 3. Documentation Bundle

**Location:** `docs/`

Curated documentation extracted from the Okapi wiki via Claude CLI. Provides per-parameter descriptions, examples, and wiki back-references that are richer than what Okapi's introspection provides.

### metadata.json

```json
{
  "generatedAt": "2026-03-31T...",
  "wikiBaseUrl": "https://okapiframework.org/wiki/index.php/",
  "aliases": {
    "okf_baseplaintext": "okf_plaintext",
    "okf_commaseparatedvalues": "okf_table",
    "okf_tabseparatedvalues": "okf_table",
    "okf_fixedwidthcolumns": "okf_table",
    "okf_paraplaintext": "okf_plaintext",
    "okf_basetable": "okf_table"
  }
}
```

| Field | Type | Description |
|-------|------|-------------|
| `wikiBaseUrl` | string | URL prefix for constructing wiki links from `wikiRef` values. |
| `aliases` | object | Maps secondary filter IDs to primary ones that share documentation (e.g. `okf_commaseparatedvalues` uses the same docs as `okf_table`). |

### concepts.json

Cross-cutting documentation for shared concepts referenced across multiple filters and steps.

```json
{
  "ruleTypes": {
    "wikiRef": "Understanding_Filter_Configurations",
    "description": "Rule types control how elements and attributes are handled...",
    "elementRuleTypes": {
      "INLINE": "Inline element...",
      "TEXTUNIT": "Text unit..."
    },
    "attributeRuleTypes": {
      "ATTRIBUTE_TRANS": "Translatable...",
      "ATTRIBUTE_WRITABLE": "Writable localizable..."
    }
  },
  "conditions": {
    "wikiRef": "Understanding_Filter_Configurations",
    "description": "Conditions are triples [attributeName, operator, value]...",
    "operators": {
      "EQUALS": "Case-insensitive string equality...",
      "NOT_EQUALS": "Case-insensitive string inequality...",
      "MATCHES": "Java regex match..."
    }
  },
  "codeFinderRules": {
    "wikiRef": "Inline_Codes_Simplifier_Step",
    "description": "Regex patterns for detecting inline codes..."
  },
  "simplifierRules": {
    "wikiRef": "Inline_Codes_Simplifier_Step",
    "description": "Rules for simplifying inline code representation...",
    "grammar": {
      "fields": ["DATA", "OUTER_DATA", "ORIGINAL_ID", "TYPE", "TAG_TYPE"],
      "flags": ["ADDABLE", "DELETABLE", "CLONEABLE"],
      "operators": ["=", "!=", "~", "!~"],
      "tagTypes": ["OPENING", "CLOSING", "STANDALONE"]
    }
  }
}
```

To construct a full wiki URL: `wikiBaseUrl + wikiRef` (from metadata.json).

### Filter Documentation (docs/filters/{filterId}.json)

```json
{
  "filterId": "okf_html",
  "filterName": "HTML/XHTML Filter",
  "wikiUrl": "https://okapiframework.org/wiki/index.php/HTML_Filter",
  "overview": "The HTML Filter extracts translatable content from HTML...",
  "limitations": [
    "Does not support template syntax (JSP, PHP, etc.) natively"
  ],
  "processingNotes": [
    "Text units flagged as non-translatable are excluded from extraction",
    "BOM is preserved if present in the input"
  ],
  "examples": [
    {
      "title": "Inline code finder for variables",
      "description": "Using the code finder to protect variable placeholders",
      "input": "useCodeFinder: true\ncodeFinderRules: ...",
      "output": "Variables like {{name}} are protected as inline codes"
    }
  ],
  "parameters": {
    "parser.assumeWellformed": {
      "description": "When enabled, the filter uses an XML parser that requires...",
      "notes": ["Faster but may fail on non-conforming HTML"]
    },
    "elements": {
      "description": "Element extraction rules that map HTML element names to...",
      "notes": [
        "Element names are case-insensitive",
        "Regex patterns are supported for element names"
      ]
    }
  }
}
```

| Field | Type | Description |
|-------|------|-------------|
| `filterId` | string | Filter identifier matching the schema filename. |
| `filterName` | string | Human-readable filter name. |
| `wikiUrl` | string | Full URL to the Okapi wiki page for this filter. |
| `overview` | string | 2-4 sentence description of the filter's purpose. |
| `limitations` | string[] | Known limitations. |
| `processingNotes` | string[] | Important behavioral details (encoding, BOM, memory, etc.). |
| `examples` | array | Worked examples with `title`, `description`, `input`, `output`. |
| `parameters` | object | Per-parameter documentation keyed by schema property name. |

**Parameter documentation object:**

| Field | Type | Description |
|-------|------|-------------|
| `description` | string | Rich description from wiki — more detailed than the schema `description`. |
| `notes` | string[] | Important notes, warnings, or caveats from the wiki. |
| `dependsOn` | array | Dependencies: `[{property: "otherParam", condition: "must be true"}]`. |
| `introducedIn` | string | Okapi version where this parameter was introduced (e.g. `"1.48.0"`). |

**Parameter name mapping:** Documentation keys match schema property names. For grouped properties, dot notation is used (e.g. `"parser.assumeWellformed"`, `"inlineCodes.enabled"`).

### Step Documentation (docs/steps/{stepId}.json)

Same structure as filter documentation, with `stepId` instead of `filterId`:

```json
{
  "stepId": "search-and-replace",
  "filterName": "Search and Replace Step",
  "wikiUrl": "https://okapiframework.org/wiki/index.php/Search_and_Replace_Step",
  "overview": "This step performs search and replace actions...",
  "parameters": {
    "regEx": {
      "description": "Enables regular expression mode for all search and replace patterns...",
      "notes": ["Placeholders for group references are $1, $2, etc."]
    },
    "dotAll": {
      "description": "Changes the meaning of the period character...",
      "dependsOn": [{ "property": "regEx", "condition": "must be true" }]
    }
  }
}
```

---

## 4. Version Tracking

**Location:** `schemas/versions.json` (source repo only, not shipped in plugin archive)

Tracks schema versions across all Okapi versions for the build system.

```json
{
  "generatedAt": "2026-03-31T...",
  "filters": {
    "okf_html": {
      "versions": [
        {
          "version": 1,
          "baseVersion": 7,
          "baseHash": "4d06b223a076",
          "overrideHash": "d1bb101ed413",
          "compositeHash": "3af115afcc53",
          "kind": "OkfHtmlFilterConfig",
          "apiVersion": "v1",
          "okapiVersions": ["0.38", "1.39.0"],
          "introducedInOkapi": "0.38"
        }
      ]
    }
  },
  "steps": {
    "search-and-replace": {
      "versions": [
        {
          "version": 1,
          "hash": "aa4220ef395d",
          "introducedInOkapi": "1.47.0",
          "okapiVersions": ["1.47.0"]
        }
      ]
    }
  }
}
```

**Filter version entry:**

| Field | Type | Description |
|-------|------|-------------|
| `version` | integer | Composite schema version number. |
| `baseVersion` | integer | Base schema version (before override merge). |
| `baseHash` | string | SHA-1 hash (12 chars) of the base schema. |
| `overrideHash` | string | SHA-1 hash of the override file. |
| `compositeHash` | string | SHA-1 hash of the merged composite. |
| `okapiVersions` | string[] | Okapi versions that produce this schema variant. |
| `introducedInOkapi` | string | First Okapi version where this variant appeared. |

**Step version entry:** Simpler — no override/composite distinction, just `hash` and version tracking.

---

## 5. Manifest

**Location:** `manifest.json`

Plugin descriptor read by neokapi at install/scan time. Declares capabilities without starting the JVM.

```json
{
  "name": "okapi",
  "version": "2.27.0",
  "framework_version": "1.47.0",
  "plugin_type": "bundle",
  "install_type": "bridge",
  "command": "java",
  "args": ["-jar", "neokapi-bridge-jar-with-dependencies.jar"],
  "capabilities": [
    {
      "type": "format",
      "id": "okf_html",
      "name": "okf_html",
      "display_name": "HTML/XHTML",
      "capabilities": ["read", "write"],
      "mime_types": ["text/html"],
      "extensions": [".html", ".htm"]
    },
    {
      "type": "tool",
      "id": "search-and-replace",
      "name": "Search and Replace",
      "description": "Performs search and replace...",
      "category": "transform",
      "inputs": ["block"],
      "tags": ["text-processing", "regex"]
    }
  ]
}
```

---

## 6. Relationship Map

How the data files relate to each other:

```
manifest.json
  └── capabilities[].id ─────────────► schemas/{id}.schema.json
                                         │
                                         ├── properties.* ──► x-editor (UI rendering)
                                         ├── $defs ─────────► elementRule, attributeRule, conditionTuple
                                         ├── x-filter ──────► configurations[] (presets with raw params)
                                         └── x-step ────────► parameterMappings, eventHandlers

docs/metadata.json
  └── aliases ──────────────────────────► maps secondary IDs to primary doc files

docs/filters/{filterId}.json
  └── parameters.{propertyName} ────────► keyed to schema property names (dot-path for groups)

docs/steps/{stepId}.json
  └── parameters.{propertyName} ────────► keyed to schema property names

docs/concepts.json
  └── ruleTypes, conditions, etc. ──────► referenced when rendering $defs types
```

**Lookup pattern for a UI:**
1. Read `manifest.json` to discover available filters/steps.
2. Load the schema (`schemas/{id}.schema.json`) for parameter structure, types, defaults, and `x-editor` UI hints.
3. Load the documentation (`docs/filters/{id}.json` or `docs/steps/{id}.json`) for descriptions, examples, and wiki links. Check `docs/metadata.json` aliases if not found directly.
4. For shared concepts (rule types, conditions, code finder), load `docs/concepts.json`.
