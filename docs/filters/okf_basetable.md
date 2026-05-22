# Table Filter

The Table Filter handles plain-text tabular documents including CSV, TSV, and fixed-width column formats. It supports multiple table types with configurable column delimiters, text qualifiers, escaping modes, and column-based extraction rules. The filter ships with six built-in configurations covering common tabular formats.

## Built-in Configurations

| Config ID | Description |
|-----------|-------------|
| `okf_table` | Generic table files (default) |
| `okf_table_csv` | Comma-Separated Values with optional header |
| `okf_table_catkeys` | Haiku CatKeys resource files |
| `okf_table_src-tab-trg` | 2-column tab-separated (source + target) |
| `okf_table_fwc` | Fixed-Width Columns |
| `okf_table_tsv` | Tab-Separated Values |

## Parameters

### Simplifier Rules

Rules for simplifying inline code representation in extracted segments. Uses the Okapi simplifier rules syntax to merge or reduce inline codes into simpler placeholders. Edited via a dedicated rules editor widget.

### Move Boundary Codes to Skeleton

*Introduced in Okapi 1.48.0*

Inline codes appearing at the very beginning or end of a segment are moved into the skeleton (non-translatable portion). This reduces clutter in translatable text when boundary codes carry no translational significance.

### Merge Adjacent Codes

*Introduced in Okapi 1.48.0*

When multiple inline codes appear consecutively with no text between them, they are combined into a single placeholder code. This simplifies the translatable content by reducing the number of inline codes translators must handle.

## Configuration Parameters (per configuration)

Each built-in configuration embeds detailed parameters controlling table parsing behavior. These include:

### Table Type
- **CSV**: Columns separated by a single character (comma, semicolon, tab, etc.)
- **TSV**: Columns separated by one or more tabs (consecutive tabs do NOT mark empty columns)
- **Fixed-width columns**: Each column has a fixed character width

### Table Properties
- **valuesStartLineNum**: Line number where table data begins (default: 1, meaning from the start)
- **columnNamesLineNum**: Line number containing column names (default: 0, meaning no column names)

Lines are numbered from 1. For typical CSV with a header, set `valuesStartLineNum=2` and `columnNamesLineNum=1`.

### CSV Options
- **fieldDelimiter**: Character separating fields in a row (default: `,`)
- **textQualifier**: Character wrapping field values to allow delimiters inside (default: `"`)

### CSV Escaping Mode
- **escapingMode=1**: Duplicate qualifier escaping — `"Text, ""quoted text"""`
- **escapingMode=2**: Backslash escaping — `"Text, \"quoted text\""`

### CSV Actions
- **removeQualifiers**: Remove qualifiers from extracted text (moved to skeleton)
- **trimMode**: Controls whitespace trimming:
  - `0`: No trimming
  - `1`: Only non-qualified fields trimmed
  - `2`: All fields trimmed
- **addQualifiers**: Add qualifiers to output values containing delimiters or line breaks

### Extraction Mode
- **sendHeaderMode**: `0` = don't extract header, `1` = column names only, `2` = all header lines
- **sendColumnsMode**: `1` = extract by column definitions, `2` = extract from all columns

### Column Detection
- **detectColumnsMode**: `0` = defined by values per row, `1` = defined by column names count, `2` = fixed number
- **numColumns**: Fixed column count (1-100, used when detectColumnsMode=2)

### Column Definitions
- **sourceColumns**: Comma-separated 1-based column indices for source text
- **targetColumns**: Comma-separated 1-based column indices for target text
- **sourceIdColumns**: Columns providing unique IDs for source columns
- **commentColumns**: Columns containing comments
- **recordIdColumn**: Column providing the record (row) ID

### Text Processing
- **trimLeading** / **trimTrailing**: Trim leading/trailing spaces and tabs
- **unescapeSource**: Convert `	`, `
`, `\`, `\uXXXX` escape sequences to characters
- **wrapMode**: Multi-line handling: `0` = separate with line feeds, `1` = unwrap (merge with space), `2` = inline codes for line breaks

### Inline Codes
- **useCodeFinder**: Enable regex-based inline code detection
- **codeFinderRules**: Okapi `#v1` format code finder rules

## Encoding Behavior

- **Input**: Unicode BOM takes priority; otherwise uses the default encoding from filter options
- **Output**: UTF-8 BOM is included only if input was UTF-8 with BOM; converting from non-UTF-8 produces no BOM
- **Line breaks**: Output preserves the original input's line-break style

## Limitations

None known.

## Examples

### CSV with header
```csv
Name,Description,Comment
"Widget","A small, useful device","For testing"
"Gadget","Another item","Optional"
```
Use `okf_table_csv` — extracts Name and Description columns as translatable text.

### Two-column source/target
```
Hello world	Bonjour le monde
Goodbye	Au revoir
```
Use `okf_table_src-tab-trg` — column 1 is source, column 2 is target.
