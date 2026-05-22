# Authoring doc-overlay files (Tier-2 AI documentation)

`doc-overlay/{filters,steps}/<id>.json` is the **committed, AI-authored** documentation
layer for the okapi-bridge. It is kept separate from Okapi-extracted facts (Tier 1:
the JSON schema in `schemas/`) and from human schema curation (Tier 3: `overrides/`).
`scripts/compose-docs.sh` composes these into `docs/`, which `assemble`/`transform`
turn into each capability's `dist/plugin/.../doc.json`.

## The contract (validated by `scripts/check-overlay.sh`)

Each file conforms to `doc-overlay/overlay.schema.json`. Required: `id`, `kind`
(`filter`|`step`), a non-empty `overview`, and an `x-provenance` block. Shape:

```json
{
  "$schema": "../overlay.schema.json",
  "id": "exact-match-word-count",
  "kind": "step",
  "name": "Exact Match Word Count Step",
  "overview": "2–4 sentences, markdown. What it does, what it operates on, primary use.",
  "limitations": ["concise markdown statements"],
  "processingNotes": ["encoding/segmentation/annotation behaviours"],
  "examples": [
    { "title": "...", "description": "...", "config": "```yaml\n...\n```", "input": null, "output": null }
  ],
  "parameters": {
    "format.fieldDelimiter": { "help": "behavioural markdown", "values": "`,` (default)", "notes": ["..."] }
  },
  "aliasOf": "okf_table",
  "x-provenance": {
    "generator": "claude-opus-4-7",
    "grounding": ["okapi-source:okapi/steps/wordcount/.../ExactMatchWordCountStep.java", "wiki:<url-if-any>", "schema"],
    "authoredAt": "2026-05-23",
    "reviewed": false
  }
}
```

## Rules

1. **Ground everything — never invent.** Read the actual Okapi Java implementation
   under `~/src/okapi/Okapi` for the class behind the capability. Cite each source you
   read in `x-provenance.grounding` (`okapi-source:<repo-relative path>`). Add `wiki:<url>`
   only if a real wiki page exists. Always include `"schema"`.
2. **Key parameters by the exact gap path.** `.gap-manifest.json` (repo root) lists, per
   id, the exact `properties` paths that need help (e.g. `format.fieldDelimiter`,
   `columnNamesLineNum`). Use those keys verbatim. Cross-check names/types/defaults in the
   composite schema: `schemas/filters/composite/<id>.v*.schema.json` or
   `schemas/steps/{composite,base}/<id>.v*.schema.json`. `help` must explain BEHAVIOUR/USAGE,
   not restate the title or type (those are Tier-1 schema facts).
3. **Voice.** Restrained, academic register. No marketing superlatives, no emoji, no
   hardcoded counts ("57 filters"). Match neokapi `docs/internals/brand-communication.md`
   and the native sidecars in neokapi `scripts/gen-refs/nativedocs/*.yaml` (e.g.
   `tools/word-count.yaml`) — that is the depth and tone expected.
4. **Sub-configuration formats** (those with `aliasOf`, listed in `doc-overlay/aliases.json`):
   write a tailored `overview` for the specific configuration (e.g. CSV vs TSV vs
   fixed-width) and only the parameters specific to it. Shared table parameters belong in
   the parent (`okf_table`) overlay — `compose-docs` merges parent ⊕ sub, so parent
   parameter help propagates to every sub-config automatically.
5. **examples-only items** already have an `overview` (migrated from the wiki parse). Open
   the existing overlay file and add an `examples[]` array (and any missing `parameters`),
   keeping the existing content and provenance; bump `generator` to your model id and add
   your grounding refs.
6. **Do NOT run the shared pipeline.** Do not run `make`, `compose-docs.sh`,
   `assemble`, `transform`, or `gen-refs` — those write shared build dirs and would race
   other agents. Validate each file you write with:
   `./scripts/check-overlay.sh doc-overlay/<filters|steps>/<id>.json`
   The orchestrator runs the full pipeline and the authoritative gap check.
