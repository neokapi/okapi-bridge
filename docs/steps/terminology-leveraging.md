# Terminology Leveraging

This step leverages glossary terms into extracted text units by querying a terminology resource connector. It sends segment content to the resource, which returns matching glossary entries that are then annotated onto source and/or target segments. Non-translatable text units are automatically skipped.

Requires both source and target languages to be configured.

## Parameters

#### Leverage
Enables or disables the step. Allows keeping the step in a pipeline while toggling it on and off without removing it.

#### Annotate Source
Enables annotation of source segment text with found glossary entries. Annotation is applied when a term is found within the source segment text.

#### Annotate Target
Enables annotation of target segment text with found glossary entries. Annotation is applied when a translation of the term is found within the target segment text.

#### Connector Class Name
Selects the terminology resource connector. Must be a subclass of `BaseTerminologyConnector` or a custom implementation of the `ITerminologyQuery` interface.

#### Connector Parameters
Serialized parameters passed to the selected terminology resource connector; the format is defined by that connector.

## Limitations

- It is **not** possible to chain several steps to leverage against multiple terminology resources.

## Notes

- Text units flagged as non-translatable are skipped.
- The terminology resource connector defines its own strategy for generating glossary entry IDs and glossary translation IDs.
- The connector splits segments into source terms or applies other techniques to find matching glossary entries.
