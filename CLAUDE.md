# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What This Is

Okapi Bridge is a Java bridge that enables [gokapi](https://github.com/gokapi/gokapi) (a Go tool) to access [Okapi Framework](https://okapiframework.org/) document format filters (57+) via gRPC. It supports 11 Okapi versions (0.38 through 1.48.0).

## Project Structure (Multi-Module Maven)

```
okapi-bridge/                      (parent pom — shared deps, plugin config)
├── bridge-core/                   (bridge source + protobuf, compiled for dev/test)
│   └── src/main/java/             (all bridge Java source)
│   └── src/main/proto/            (gRPC protobuf definitions)
│   └── src/test/java/             (unit tests)
├── tools/schema-generator/        (schema gen tool, depends on bridge-core)
├── okapi-releases/{version}/      (per-version build, inherits parent config)
│   ├── pom.xml                    (auto-generated: parent ref + filter deps)
│   ├── meta.json                  (version metadata)
│   └── schemas/                   (generated JSON schemas)
└── schemas/                       (centralized composite schemas)
```

**Key design**: Infrastructure deps (gRPC, Gson, etc.) and plugin config live in the parent pom. Per-version poms only declare Okapi filter dependencies. Adding a new dep to the bridge only requires updating the parent — zero per-version pom changes needed.

## Build & Test Commands

```bash
# Build for a specific Okapi version (compiles bridge source + shades with version's filters)
make build V=1.47.0

# Run tests (bridge-core against latest Okapi)
make test

# Run single test class
mvn test -pl bridge-core -Dokapi.version=1.47.0 -Dtest=ParameterApplierTest

# Run single test method
mvn test -pl bridge-core -Dokapi.version=1.47.0 -Dtest=ParameterApplierTest#applyParameters_booleanValue_shouldApply

# Build reactor (bridge-core + schema-generator)
mvn install -DskipTests
```

Java 11 for Okapi 0.38–1.47.0, Java 17 for 1.48.0+. Each per-version build compiles against its exact Okapi version and Java target.

## Schema Management

```bash
make add-release V=1.48.0       # Add new Okapi version (full pipeline)
make regenerate V=1.47.0        # Regenerate schemas for one version
make regenerate-composites      # Regenerate composites after override edits
make schema-matrix              # Update README schema matrix
```

## Architecture

### gRPC Protocol

`OkapiBridgeServer` → `BridgeServiceImpl` → Okapi filters. All logging to stderr.

### Data Flow

1. **Inbound**: `BridgeServiceImpl` receives gRPC commands, uses `FilterRegistry` to find/instantiate filters, applies parameters via `ParameterApplier`
2. **Read path**: `EventConverter` transforms Okapi `Event` objects into `PartDTO` trees (Block, Layer, GroupStart/End, Data, Media)
3. **Write path**: `PartDTOConverter` applies translations from `PartDTO`s back onto Okapi Events, then the filter writer produces the translated document

### Key Classes

- `OkapiBridgeServer` — Entry point, gRPC server
- `BridgeServiceImpl` — gRPC service implementation
- `ProtoAdapter` — DTO ↔ Protobuf message conversion
- `FilterRegistry` — Dynamic filter discovery by scanning okapi-filter-* JARs on classpath
- `EventConverter` — Okapi Event → PartDTO (JSON-serializable)
- `PartDTOConverter` — PartDTO translations → Okapi Event (for writing)
- `ParameterApplier` — Applies JSON params to Okapi `IParameters` (handles codeFinderRules, reflection for complex setters)
- `ParameterConverter` — Bidirectional codeFinderRules format conversion (clean JSON ↔ Okapi #v1 format)
- `model/` — DTOs: CommandMessage, ResponseMessage, PartDTO, BlockDTO, LayerDTO, SegmentDTO, SpanDTO, FragmentDTO, etc.

### Plugin Manifest

A single `manifest.json` serves as both the install-time and runtime descriptor. It uses an MCP-inspired `command`/`args`/`env` pattern for process launching:

```json
{
  "name": "okapi-bridge",
  "version": "2.0.0",
  "plugin_type": "bundle",
  "install_type": "bridge",
  "command": "java",
  "args": ["-jar", "gokapi-bridge-jar-with-dependencies.jar"],
  "capabilities": [...]
}
```

The CI release workflow generates this manifest at build time by querying the bridge for filter capabilities and embedding the runtime launch configuration.

### Three-Layer Schema Architecture

1. **Base schemas** (`schemas/base/okf_*.vN.schema.json`) — Auto-generated from Okapi filter introspection. Versioned per-filter.
2. **Overrides** (`schemas/overrides/okf_*.overrides.json`) — Human-curated UI hints (groups, widgets, presets). Single file per filter.
3. **Composite schemas** (`schemas/composite/okf_*.vN.schema.json`) — Merged base + override, served to consumers. Version increments when either base or override content changes.

`schema-versions.json` tracks all versions with content hashes (first 12 chars of SHA1 of canonical JSON).

### Multi-Version Support

Each `okapi-releases/{version}/` directory contains:
- `pom.xml` — Auto-generated by `scripts/generate-version-pom.sh` (discovers available filter artifacts from Maven Central). Inherits from parent pom — only declares version-specific filter deps.
- `meta.json` — Version metadata (okapiVersion, javaVersion, filterCount)
- `schemas/` — Per-version generated schemas

The parent `pom.xml` declares all infrastructure dependencies (gRPC, Gson, SnakeYAML, etc.) and plugin configurations. Per-version poms inherit these and add only their Okapi filter dependencies. This means adding a new infrastructure dep only touches the parent pom.

## Conventions

- stdout is **only** for gRPC; all logging to stderr
- Filter IDs use `okf_` prefix (e.g., `okf_html`, `okf_json`)
- YAML 1.2 boolean resolution: `FilterRegistry` uses a custom SnakeYAML Resolver so `yes`/`no` are strings, not booleans
- Schema `$version` format is `N.0.0` where N increments on content change
- CI on push to main: regenerates composites and verifies schemas are up-to-date (fails if out of date)
- CI on tag push: matrix build across all 11 Okapi versions
