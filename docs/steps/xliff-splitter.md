# XLIFF Splitter

Splits a single XLIFF document into multiple documents, one per `<file>` element. All content outside `<file>` elements is copied as-is into each split document. An XLIFF document with only one `<file>` element is written out unaltered. Use the **XLIFF Joiner Step** to reassemble split files.

Takes: Raw document. Sends: Raw document.

## Parameters

#### Process Big File
Uses a **stream-based parser** instead of the default DOM-based parser. This allows processing very large XLIFF input documents (e.g. several hundreds of MB) that would not fit into memory with the DOM parser.

> **Limitation:** When enabled, any XML parts between `<file>` elements are ignored and not copied into the new files.

#### File Marker
A marker string appended to the end of each generated output filename, just before the numeric counter.

- `_PART` → `document_PART1.xlf`, `document_PART2.xlf`
- `_split` → `document_split1.xlf`, `document_split2.xlf`

Default: `_PART`

#### Update WorldServer Translation Status
Updates the `translation_type` and `translation_status` flags in `<iws:status>` elements of the output documents. This also **removes** the `target_content` attribute. This is specific to **WorldServer XLIFF** documents.

#### Value for 'translation_type'
The value written into the `translation_type` attribute of `<iws:status>` elements in each output document. Only used when **Update WorldServer Translation Status** is enabled.

Default: `manual_translation`

#### Value for 'translation_status'
The value written into the `translation_status` attribute of `<iws:status>` elements in each output document. Only used when **Update WorldServer Translation Status** is enabled.

Default: `finished`

#### Restore Original File Names
Restores the original file names of the split output documents instead of using the generated numbered names with the file marker.

## Limitations

- When using the **Process large files** option, any XML parts between `<file>` elements are ignored and not copied into the new files.
- An XLIFF document with only one `<file>` element is written out unaltered (no splitting occurs).

## Notes

- The received event is unaltered — the step outputs multiple numbered XLIFF documents in the output path.
- All content outside `<file>` elements (e.g. `<header>`, processing instructions) is copied as-is to each split document.
- The default DOM-based parser requires the entire file to fit in memory; use **Process big file** for very large inputs.
- Use the **XLIFF Joiner Step** to reassemble files that were split with this step.
