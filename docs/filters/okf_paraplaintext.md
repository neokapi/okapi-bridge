# Plain Text Filter

The Plain Text filter processes text files encoded in ANSI, Unicode, UTF-8, and UTF-16 with automatic Byte-Order-Mark detection. It provides three extraction modes: **line-based** (`okf_baseplaintext`), **paragraph-based** (`okf_paraplaintext`), and **regex-based** (`okf_regexplaintext`), plus a **spliced lines** variant (`okf_splicedlines`) for source code with line-continuation characters. Each mode shares common parser and inline code settings but exposes different extraction parameters.

## Parameters

### Extraction (Paragraph Mode)

#### Extract by Paragraphs (`extraction.extractParagraphs`)
Groups consecutive non-empty lines into paragraph text units, separated by one or more blank lines. Each paragraph becomes a single translatable unit with internal line breaks handled according to `wrapMode`. When disabled, each line becomes its own text unit.

> Only available in the paragraph-based sub-filter (`okf_paraplaintext`).

### Extraction (Regex Mode)

#### Extraction Rule (`extraction.rule`)
Java regular expression used to extract translatable text. The filter applies this regex to the input and creates text units from matches. Use capturing groups to isolate the translatable portion, then set `sourceGroup` to the group index.

Examples:
- `(^(?=.+))(.*?)$` — extract each non-empty line (group 2 = content, with MULTILINE flag)
- `(
*)(.*)(

|\Z)` — extract paragraphs separated by blank lines (group 2, with MULTILINE + DOTALL)

#### Source Group (`extraction.sourceGroup`)
The 1-based index of the capturing group in the extraction regex whose match contains the translatable text. Group 0 is the entire match.

#### Regex Options (`extraction.regexOptions`)
Bitmask of `java.util.regex.Pattern` flags:
- **8** = `MULTILINE` — `^` and `$` match line boundaries
- **32** = `DOTALL` — `.` matches newlines
- **40** = `MULTILINE | DOTALL`

#### Sample Text (`extraction.sample`)
Sample text for interactively testing the extraction regex. Extracted matches display in square brackets. Does not affect processing.

### Extraction (Spliced Lines Mode)

#### Splicer Character (`extraction.splicer`)
The character at the end of a line indicating continuation on the next line. Common values: `\` (C/shell), `_` (VB).

#### Create Placeholders for Splicers (`extraction.createPlaceholders`)
Creates inline code placeholders where splicer characters were removed, preserving original line break positions.

### Parser Settings

#### Trim Leading Whitespace (`parser.trimLeading`)
Strips leading spaces and tabs from each extracted text unit.

#### Trim Trailing Whitespace (`parser.trimTrailing`)
Strips trailing spaces and tabs from each extracted text unit.

#### Unescape Source (`parser.unescapeSource`)
Converts escape sequences (`	`, `
`, `\`, `\uXXXX`) into their corresponding characters before extraction.

#### Preserve Whitespace (`parser.preserveWS`)
Preserves original whitespace in extracted text units.

#### Line Wrapping Mode (`parser.wrapMode`)
Controls how multiple lines combine into a single text unit:
- **NONE** — Lines separated by `
` line feeds
- **SPACES** — Lines unwrapped and merged with spaces
- **PLACEHOLDERS** — Inline code placeholders for line breaks

### Inline Codes

#### Enable Inline Codes (`inlineCodes.enabled`)
Activates regex-based detection of inline codes within extracted text. Matches are converted to inline code placeholders.

#### Code Finder Rules (`inlineCodes.rules`)
Regex patterns identifying inline codes (format specifiers, escape sequences, etc.). Default rules match C printf format specifiers and escape sequences.

#### Merge Adjacent Codes (`inlineCodes.mergeAdjacent`)
Merges consecutive inline codes into a single placeholder.

#### Move Boundary Codes (`inlineCodes.moveBoundaryCodes`)
Moves inline codes at segment boundaries to the skeleton (non-translatable content).

#### Simplifier Rules (`inlineCodes.simplifierRules`)
Rules for simplifying inline code representation.

## Processing Notes

- Auto-detects Unicode BOM to determine encoding; falls back to configured default encoding if no BOM found.
- UTF-8 output includes BOM only if input was UTF-8 and had a BOM.
- Output line-break type preserves the original input convention (CR, LF, or CRLF).
- The regex sub-filter detects a wider range of linebreaks at the cost of lower speed and higher memory usage.

## Limitations

None known.

## Examples

### Line-by-line with trailing trim
```json
{
  "parser": {
    "trimLeading": false,
    "trimTrailing": true,
    "unescapeSource": true,
    "wrapMode": "NONE"
  }
}
```

### Paragraph extraction
```json
{
  "extraction": { "extractParagraphs": true },
  "parser": { "preserveWS": true, "wrapMode": "NONE" }
}
```

Input:
```text
This is the first sentence.
And this continues the paragraph.

This is a new paragraph.
```

Output: two text units — the first paragraph as one unit, the second as another.

### Regex extraction (line-by-line)
```json
{
  "extraction": {
    "rule": "(^(?=.+))(.*?)$",
    "sourceGroup": 2,
    "regexOptions": 8
  }
}
```

### Spliced lines (backslash)
```json
{
  "extraction": {
    "splicer": "\",
    "createPlaceholders": true
  }
}
```
