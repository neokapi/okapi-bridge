# OpenOffice / ODF Filter

The OpenOffice Filter processes OpenOffice.org documents in OpenDocument format (ODF): ODT (text), ODS (spreadsheet), ODP (slides), ODG (graphics), and their corresponding template formats. The filter internally wraps an ODF Filter for raw OpenDocument XML files. Documents are ZIP archives containing multiple sub-documents (`content.xml`, `meta.xml`, `style.xml`), each treated independently during extraction.

## Parameters

#### Extract Notes
Extracts the content of `<office:annotation>` elements (notes/comments) as translatable text. When disabled, annotations are skipped entirely during extraction.

> **Known issue:** This option may not take effect — notes may be extracted regardless of the setting.

#### Extract References
Extracts the content of `<text:bookmark-ref>` elements. These elements contain a **copy** of the referenced text that is automatically updated by OpenOffice when the document is refreshed.

Any translation of referenced text will be **overwritten** the next time the document updates its references. However, enabling this can be useful to provide context — the referenced text appears inline within the segment where it is inserted.

> **Known issue:** This option may not take effect — references may be extracted regardless of the setting.

> **Warning:** Translations of bookmark references will be lost when OpenOffice updates the document, since the reference content is regenerated from the referent.

#### Extract Metadata
Extracts translatable metadata from `meta.xml` within the ODF package (e.g., document title, subject, keywords). Enabled by default.

#### Encode Character Entity Reference Glyphs
Controls whether special glyphs are encoded as character entity references in the output. Enabled by default.

## Limitations

- Some deleted text may get extracted. Accept or reject all revision changes before processing the input document.
- The `extractNotes` and `extractReferences` options may not work — content may be extracted regardless of settings.
- Sequential tabs may be reduced to a single tab during extraction and merge round-trip due to incorrect handling of space/tab elements on input.
- The target (output) encoding **must** be set to UTF-8 when extracting documents intended to be merged back.

## Notes

- Input encoding is automatically detected; any user-specified encoding is ignored. Output is always UTF-8.
- Line-breaks in output are always simple linefeed (LF).
- ODF documents are ZIP archives. The filter processes `content.xml`, `meta.xml`, and `style.xml` as separate sub-documents. In XLIFF output, each becomes a separate `<file>` element.
- Typically only `content.xml` contains extracted text; `meta.xml` and `style.xml` are often empty.
- If you need to process a raw ODF XML file directly (not packaged as a ZIP), use the ODF Filter instead.
