# Multi-Parsers Filter

The Multi-Parsers Filter extracts translatable text from two-level complex formats, where an outer format contains inner formats. A typical use case is a CSV file where some columns contain Markdown, some HTML, and some plain text. By default, the filter is configured to process a CSV file where all columns are translatable and treated as plain text.

## Parameters

#### Columns Not Extracted (`csvNoExtractCols`)
Comma-separated list of 0-based column indexes whose content is sent to the skeleton rather than extracted. Empty by default (all columns extracted). Ignored when auto-detection is enabled.

#### Column Sub-Filters (`csvFormatCols`)
Comma-separated `index:filterId` pairs mapping a 0-based column index to the sub-filter that parses that column (e.g. `1:okf_markdown, 5:okf_html`). Columns not listed are treated as plain text. Ignored when auto-detection is enabled.

#### Starting Row (`csvStartingRow`)
1-based row number at which extraction begins. Set to `2` to skip a single header row. Default `1`.

#### Auto-Detect Column Types (`csvAutoDetectColumnTypes`)
When enabled, per-column extraction types are read from a designated row of each input file rather than from the static configuration. Default `false`.

#### Auto-Detect Column Types Row (`csvAutoDetectColumnTypesRow`)
1-based row number holding the per-column type tokens (e.g. `notrans,text,okf_html,okf_markdown,text`) when auto-detection is enabled. Default `2`.

## Limitations

- This filter is **BETA** — behavior and parameters may change in future releases.

## Notes

- If the input file has a Unicode Byte-Order-Mark (BOM), the corresponding encoding (UTF-8, UTF-16, etc.) is used automatically.
- If no BOM is present, the default encoding specified in the filter options is used.
