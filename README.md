# Okapi Bridge

A Java bridge for [gokapi](https://github.com/gokapi/gokapi) that provides access to [Okapi Framework](https://okapiframework.org/) filters. This enables gokapi to process 40+ document formats through Okapi's proven filter implementations.

## Features

- **57+ Filters**: HTML, XML, XLIFF, OpenXML, Markdown, JSON, YAML, PO, and many more
- **Centralized Schemas**: Deduplicated base schemas with composite versioning
- **Multi-version Support**: Builds for 10 Okapi versions (0.38 to 1.47.0)
- **gRPC Protocol**: Streaming bridge protocol for Go ↔ JVM communication
- **Per-version Dependencies**: Each Okapi version has its own discovered filter dependencies

## Installation

Download a release from GitHub or use gokapi's plugin installer:

```bash
kapi plugins install okapi-bridge
```

## Project Structure

```
okapi-bridge/
├── bridge-core/                # Bridge runtime source + tests
│   └── src/main/java/          # gRPC server, event conversion, parameter handling
├── tools/schema-generator/     # Schema generation tool (Java 17+)
│   └── src/main/resources/
│       ├── groupings.json      # Per-filter param → group + clean name mappings
│       └── common.defs.json    # Shared $defs (inlineCodes, whitespace, etc.)
├── schemas/                    # Centralized schema storage
│   ├── base/                   # Auto-generated hierarchical base schemas (versioned)
│   ├── composite/              # Merged schemas (base + overrides, served to consumers)
│   ├── overrides/              # Human-curated UI hints (widgets, presets, descriptions)
│   │   └── _fragments/         # Shared override fragments ($include)
│   └── defs/
│       └── common.defs.json    # Canonical shared type definitions
├── okapi-releases/             # Per-version configuration
│   ├── 1.47.0/
│   │   ├── pom.xml             # Version-specific dependencies (auto-generated)
│   │   └── schemas/            # Generated schemas for this version
│   └── ...
├── scripts/
│   ├── centralize-schemas.sh   # Orchestrates schema centralization + versioning
│   ├── merge-schema.sh         # Merges base + override (walks nested properties)
│   └── generate-version-pom.sh # Discovers filters for Okapi version
├── schema-versions.json        # Version tracking with content hashes
├── pom.xml                     # Parent pom (shared deps, plugin config)
└── Makefile                    # Build automation
```

### Schema Architecture

The project uses a three-layer centralized schema architecture:

1. **Base Schemas** (`schemas/base/`): Auto-generated from Okapi filter introspection. Versioned per-filter (e.g., `okf_html.v1.schema.json`, `okf_html.v2.schema.json`).

2. **Overrides** (`schemas/overrides/`): Human-curated UI hints (widgets, presets, descriptions). Single file per filter that applies to all versions.

3. **Composite Schemas** (`schemas/composite/`): Final schemas served to consumers (base + override merged). Versioned when composite content changes.

4. **schema-versions.json**: Index tracking base version, base hash, override hash, and composite hash for each filter version.

### Hierarchical Schemas

Okapi filters use flat key-value parameters internally (e.g., `extractAllPairs`, `useKeyAsName`, `codeFinderRules`). The schema generator restructures these into **nested groups with clean property names**, making schemas more intuitive for UI rendering and API consumers.

Each renamed property carries an `x-flattenPath` annotation mapping back to the original Okapi parameter name:

```json
{
  "properties": {
    "extraction": {
      "type": "object",
      "description": "Control what content is extracted for translation",
      "properties": {
        "extractAll": {
          "type": "boolean",
          "default": true,
          "x-flattenPath": "extractAllPairs",
          "description": "Extract all key-value pairs"
        }
      }
    },
    "inlineCodes": {
      "$ref": "#/$defs/inlineCodes"
    }
  },
  "$defs": {
    "inlineCodes": { ... },
    "codeFinderRules": { ... }
  }
}
```

**Key files:**

- `tools/schema-generator/src/main/resources/groupings.json` — Per-filter param-to-group mappings with clean names
- `schemas/defs/common.defs.json` — Shared `$defs` (inlineCodes, whitespace, codeFinderRules, simplifierRules) referenced via `$ref`
- `bridge-core/.../util/ParameterFlattener.java` — Runtime converter: walks hierarchical configs and uses `x-flattenPath` to produce flat Okapi params

**Backwards compatibility:** The `ParameterFlattener` passes flat input through unchanged, so older clients sending flat params continue to work.

**Override hints** (descriptions, widgets, presets) are applied into nested properties using auto-resolution — a field name like `"extractAll"` is automatically found inside group properties, or you can use explicit dot-paths like `"extraction.extractAll"`.

### Composite Versioning

The composite version increments when either the base schema OR the override changes:

| Scenario | Base | Override | Composite | Result |
|----------|------|----------|-----------|--------|
| Initial | v1 | - | v1 | Same content |
| Add override | v1 | v1 | v2 | New version (override added) |
| Update override | v1 | v2 | v3 | New version (override changed) |
| Okapi release changes params | v2 | v2 | v4 | New version (base changed) |
| Okapi release, no changes | v1 | v2 | v3 | Same version (hash unchanged) |

<!-- SCHEMA_MATRIX_START -->
### Schema Statistics

- **Total filters**: 57
- **Total schema versions**: 139
- **Filters with version changes**: 41

### Schema Version Matrix

Shows which composite schema version applies to each Okapi release.
Only filters with multiple versions are shown (`-` = filter not available).

| Filter | 0.38 | 1.39.0 | 1.40.0 | 1.41.0 | 1.42.0 | 1.43.0 | 1.44.0 | 1.45.0 | 1.46.0 | 1.47.0 | 1.48.0 |
|--------|------|------|------|------|------|------|------|------|------|------|------|
| `okf_archive` | **v1** | v1 | v1 | v1 | v1 | v1 | v1 | v1 | **v2** | v2 | v2 |
| `okf_autoxliff` | **v1** | v1 | v1 | v1 | v1 | v1 | v1 | **v2** | v2 | v2 | v2 |
| `okf_baseplaintext` | **v1** | v1 | v1 | v1 | v1 | v1 | v1 | v1 | **v2** | v2 | v2 |
| `okf_basetable` | **v1** | v1 | v1 | v1 | v1 | v1 | v1 | v1 | **v2** | **v3** | v3 |
| `okf_commaseparatedvalues` | **v1** | v1 | v1 | v1 | v1 | v1 | v1 | v1 | **v2** | **v3** | v3 |
| `okf_doxygen` | **v1** | v1 | v1 | v1 | v1 | v1 | v1 | v1 | **v2** | v2 | v2 |
| `okf_dtd` | **v1** | v1 | v1 | v1 | v1 | v1 | v1 | v1 | **v2** | v2 | **v3** |
| `okf_fixedwidthcolumns` | **v1** | v1 | v1 | v1 | v1 | v1 | v1 | v1 | **v2** | **v3** | v3 |
| `okf_html` | **v1** | v1 | **v2** | v2 | **v3** | **v4** | v4 | **v5** | v5 | v5 | **v6** |
| `okf_icml` | **v1** | v1 | v1 | v1 | **v2** | v2 | v2 | v2 | **v3** | v3 | v3 |
| `okf_idml` | **v1** | **v2** | v2 | **v3** | v3 | v3 | **v4** | **v5** | **v6** | v6 | **v7** |
| `okf_json` | **v1** | v1 | v1 | v1 | **v2** | v2 | v2 | v2 | **v3** | v3 | **v4** |
| `okf_markdown` | **v1** | v1 | v1 | v1 | **v2** | **v3** | v3 | **v4** | v4 | v4 | v4 |
| `okf_messageformat` | - | - | - | - | - | - | - | - | **v1** | **v2** | v2 |
| `okf_mif` | **v1** | **v2** | **v3** | **v4** | v4 | v4 | v4 | v4 | **v5** | v5 | v5 |
| `okf_mosestext` | **v1** | v1 | v1 | v1 | v1 | v1 | v1 | v1 | **v2** | v2 | v2 |
| `okf_odf` | **v1** | v1 | v1 | v1 | v1 | v1 | v1 | v1 | **v2** | v2 | v2 |
| `okf_openoffice` | **v1** | v1 | v1 | v1 | v1 | v1 | v1 | v1 | **v2** | v2 | v2 |
| `okf_openxml` | **v1** | v1 | v1 | **v2** | v2 | **v3** | v3 | **v4** | **v5** | **v6** | **v7** |
| `okf_paraplaintext` | **v1** | v1 | v1 | v1 | v1 | v1 | v1 | v1 | **v2** | v2 | v2 |
| `okf_pdf` | **v1** | v1 | v1 | v1 | v1 | v1 | v1 | v1 | v1 | v1 | **v2** |
| `okf_pensieve` | **v1** | v1 | v1 | v1 | v1 | v1 | v1 | v1 | **v2** | v2 | v2 |
| `okf_plaintext` | **v1** | v1 | v1 | v1 | **v2** | v2 | v2 | v2 | v2 | v2 | v2 |
| `okf_po` | **v1** | v1 | v1 | v1 | **v2** | v2 | v2 | v2 | v2 | v2 | v2 |
| `okf_properties` | **v1** | v1 | **v2** | v2 | v2 | v2 | v2 | v2 | v2 | v2 | v2 |
| `okf_regex` | **v1** | v1 | v1 | v1 | **v2** | **v3** | v3 | v3 | **v4** | v4 | v4 |
| `okf_rtf` | **v1** | v1 | v1 | v1 | v1 | v1 | v1 | v1 | **v2** | v2 | v2 |
| `okf_sdlpackage` | **v1** | v1 | v1 | v1 | v1 | v1 | v1 | v1 | **v2** | v2 | v2 |
| `okf_splicedlines` | **v1** | v1 | v1 | v1 | v1 | v1 | v1 | v1 | **v2** | v2 | v2 |
| `okf_table` | **v1** | v1 | v1 | v1 | **v2** | v2 | v2 | v2 | v2 | v2 | v2 |
| `okf_tabseparatedvalues` | **v1** | v1 | v1 | v1 | v1 | v1 | v1 | v1 | **v2** | **v3** | v3 |
| `okf_tex` | **v1** | v1 | v1 | v1 | v1 | v1 | v1 | v1 | **v2** | v2 | v2 |
| `okf_transtable` | **v1** | v1 | v1 | v1 | v1 | v1 | v1 | v1 | **v2** | v2 | v2 |
| `okf_ttml` | - | - | - | - | - | - | - | - | **v1** | v1 | **v2** |
| `okf_vtt` | - | - | - | - | - | - | - | - | **v1** | v1 | **v2** |
| `okf_wsxzpackage` | - | - | - | - | - | - | - | **v1** | **v2** | v2 | v2 |
| `okf_xliff` | **v1** | **v2** | v2 | **v3** | **v4** | **v5** | v5 | v5 | **v6** | **v7** | **v8** |
| `okf_xliff2` | **v1** | v1 | v1 | **v2** | **v3** | **v4** | v4 | **v5** | **v6** | **v7** | v7 |
| `okf_xml` | **v1** | v1 | v1 | v1 | **v2** | v2 | v2 | v2 | v2 | **v3** | v3 |
| `okf_xmlstream` | **v1** | v1 | v1 | v1 | v1 | **v2** | v2 | v2 | v2 | v2 | v2 |
| `okf_yaml` | **v1** | v1 | v1 | v1 | v1 | v1 | v1 | v1 | **v2** | v2 | v2 |
<!-- SCHEMA_MATRIX_END -->

## Schema Management

### Makefile Targets

```bash
make help              # Show all targets

# Discovery
make list-upstream     # Query Maven Central for available Okapi versions
make list-local        # List local okapi-releases/ directories

# Centralized Schema Management
make centralize           # Migrate to centralized schema structure
make regenerate-composites # Regenerate composites from base + overrides
make schema-matrix         # Generate schema version matrix

# Add New Version
make add-release V=1.48.0  # Add new Okapi version (generates pom, schemas)

# Dependencies
make generate-pom V=1.47.0  # Generate version-specific pom.xml
make generate-all-poms      # Generate pom.xml for all versions

# Build
make build V=1.47.0    # Build JAR for specific version
make test              # Run tests
```

## Adding a New Okapi Version

1. Check available versions:
   ```bash
   make list-upstream
   ```

2. Add the new version (auto-generates pom.xml and updates centralized schemas):
   ```bash
   make add-release V=1.48.0
   ```

3. Commit and push:
   ```bash
   git add okapi-releases/1.48.0 schemas/ schema-versions.json
   git commit -m "feat: Add Okapi 1.48.0 support"
   git push
   ```

4. Create a release (triggers build for all versions):
   ```bash
   git tag v1.6.0
   git push origin v1.6.0
   ```

## Updating Overrides

Overrides add UI hints (widgets, presets, descriptions) to individual schema properties. Field names are auto-resolved into nested groups, so you can use simple names:

```json
{
  "fields": {
    "extractAll": {
      "description": "Extract all key-value pairs for translation"
    },
    "codeFinderRules": {
      "presets": { "html": { ... }, "printf": { ... } }
    }
  }
}
```

Or explicit dot-paths for precision: `"extraction.extractAll"`.

1. Edit the override file:
   ```bash
   vim schemas/overrides/okf_json.overrides.json
   ```

2. Regenerate and commit:
   ```bash
   make regenerate-composites
   git add schemas/ schema-versions.json
   git commit -m "Improve JSON filter override hints"
   git push
   ```

## CI/CD

### Nightly Release Check

A scheduled workflow runs daily to check Maven Central for new Okapi releases. If a new version is found, it automatically:
1. Discovers available filters and generates `pom.xml`
2. Generates base schemas and updates centralized composites
3. Creates a PR for review

### On Push to Main (CI)

- Builds and tests with the latest Okapi version
- Regenerates composite schemas if overrides changed
- Auto-commits regenerated files

### Snapshot Builds (Nightly)

A daily workflow builds okapi-bridge against the latest unreleased Okapi source (`main` branch):
1. Clones and builds Okapi from source
2. Generates a version pom and builds the bridge JAR
3. Runs bridge-core tests and a smoke test
4. Packages a release archive and creates/updates a `snapshot` pre-release on GitHub
5. Updates `channels/snapshot.json` in the plugin registry

Only one snapshot release exists at a time — each run replaces the previous one. To install:

```bash
kapi plugins install okapi-bridge --channel snapshot
```

The workflow can also be triggered manually with a custom Okapi git ref:

```bash
gh workflow run snapshot.yml -f okapi_ref=some-branch
```

### On Tag Push (Release)

1. **Setup job** scans `okapi-releases/` to get version list
2. **Build matrix** compiles a JAR for each Okapi version in parallel
3. **Release job** creates GitHub release with all artifacts
4. **Registry job** updates the gokapi plugin registry

## Development

### Prerequisites

- Java 11+ (Java 17+ for Okapi 1.48.0 and later)
- Maven 3.6+
- jq (for Makefile targets)
- curl (for filter discovery)

### Java Version Requirements

The project supports multiple Okapi versions with different Java requirements:

| Okapi Version | Java Version |
|---------------|--------------|
| 0.38 - 1.47.0 | 11           |
| 1.48.0+       | 17           |

Each `okapi-releases/{version}/meta.json` contains the required Java version:
```json
{
  "okapiVersion": "1.48.0",
  "javaVersion": "17",
  "generatedAt": "2026-02-17T10:00:00Z",
  "filterCount": 56
}
```

The CI/CD workflows and Makefile automatically select the correct Java version based on this metadata.

### Project Structure

```
okapi-bridge/
├── bridge-core/                # Bridge runtime + gRPC server (Java 11+)
├── tools/schema-generator/     # Schema generator (Java 17+, standalone)
├── schemas/                    # Centralized schema storage (base, composite, overrides)
├── okapi-releases/{version}/   # Per-version config with meta.json + schemas
└── scripts/                    # Build automation scripts
```

### Building

```bash
# Build with version-specific dependencies (auto-selects Java version)
make build V=1.48.0

# Build schema generator tools (requires Java 17)
make build-tools

# Build root project (bridge runtime)
mvn package

# Run tests
make test
```

### Schema Commands

```bash
# Regenerate all schemas from scratch
make centralize

# Regenerate composites only (after override changes)
make regenerate-composites
```

## License

Apache 2.0 (same as Okapi Framework)
