# Okapi Bridge Makefile
# Manages schema generation across Okapi versions with Java version separation

SHELL := /bin/bash
.PHONY: help list-upstream list-local add-release regenerate regenerate-all \
        version-schemas build clean test generate-pom generate-all-poms \
        centralize regenerate-composites build-tools \
        download-filter-docs parse-filter-docs parse-filter-docs-force \
        bundle-filter-docs clean-filter-docs

# Configuration - derived from okapi-releases directory
SUPPORTED_VERSIONS := $(shell ls -1 okapi-releases 2>/dev/null | sort -V)
LATEST_VERSION := $(shell ls -1 okapi-releases 2>/dev/null | sort -V | tail -1)
VERSIONS_FILE := schema-versions.json

# Default target
help:
	@echo "Okapi Bridge - Schema and Release Management"
	@echo ""
	@echo "Discovery:"
	@echo "  make list-upstream        Query Maven Central for available Okapi versions"
	@echo "  make list-local           List local okapi-releases/ directories"
	@echo ""
	@echo "Schema Management (Centralized):"
	@echo "  make centralize           Migrate to centralized schema structure"
	@echo "  make regenerate-composites  Regenerate composites from base + overrides"
	@echo "  make add-release V=1.48.0 Add new Okapi version"
	@echo ""
	@echo "Build:"
	@echo "  make build V=1.47.0       Build JAR for specific Okapi version"
	@echo "  make build-tools          Build schema generator tools (Java 17)"
	@echo "  make test                 Run tests (bridge-core)"
	@echo "  make clean                Clean build artifacts"
	@echo ""
	@echo "Documentation:"
	@echo "  make download-filter-docs  Download filter docs from Okapi wiki"
	@echo "  make parse-filter-docs     Parse docs into structured JSON (uses Claude CLI)"
	@echo "  make bundle-filter-docs    Bundle parsed docs into filter-docs-bundle.json"
	@echo "  make clean-filter-docs     Remove downloaded docs"
	@echo ""
	@echo "Dependencies:"
	@echo "  make generate-pom V=1.47.0  Generate version-specific pom.xml"
	@echo "  make generate-all-poms      Generate pom.xml for all versions"
	@echo ""
	@echo "Supported versions: $(SUPPORTED_VERSIONS)"
	@echo "Latest version: $(LATEST_VERSION)"

# ============================================================================
# Discovery
# ============================================================================

list-upstream:
	@echo "Available Okapi versions in Maven Central:"
	@curl -s "https://search.maven.org/solrsearch/select?q=g:net.sf.okapi+AND+a:okapi-core&core=gav&rows=50&wt=json" \
		| jq -r '.response.docs[].v' | sort -V

list-local:
	@echo "Local okapi-releases/ directories:"
	@ls -1 okapi-releases/ 2>/dev/null | grep -E '^[0-9]' | sort -V || echo "  (none)"
	@echo ""
	@echo "Latest version: $(LATEST_VERSION)"

# ============================================================================
# Centralized Schema Management
# ============================================================================

# Migrate to centralized schema structure
centralize:
	@./scripts/centralize-schemas.sh

# Regenerate composite schemas from base + overrides
regenerate-composites:
	@./scripts/centralize-schemas.sh regenerate-composites

# Generate schema version matrix
schema-matrix:
	@./scripts/generate-matrix.sh

# ============================================================================
# Legacy Schema Management
# ============================================================================

# Add a new Okapi release
add-release:
ifndef V
	$(error V is required. Usage: make add-release V=1.48.0)
endif
	@echo "Adding Okapi release $(V)..."
	@mkdir -p okapi-releases/$(V)/schemas
	@# Discover available filters and generate version-specific pom.xml
	@echo "Discovering available filters..."
	@./scripts/generate-version-pom.sh $(V)
	@# Compile with version-specific dependencies
	@echo "Compiling with Okapi $(V) dependencies..."
	@mvn -B -q compile -f okapi-releases/$(V)/pom.xml
	@# Generate schemas using schema-generator tools
	@echo "Generating schemas for Okapi $(V)..."
	@mvn -B exec:java@generate-schemas -Dexec.args="okapi-releases/$(V)/schemas" -f okapi-releases/$(V)/pom.xml
	@# Create meta.json
	@echo '{"okapiVersion":"$(V)","generatedAt":"'$$(date -u +%Y-%m-%dT%H:%M:%SZ)'","filterCount":'$$(ls -1 okapi-releases/$(V)/schemas/*.schema.json 2>/dev/null | wc -l | tr -d ' ')'}' \
		| jq . > okapi-releases/$(V)/schemas/meta.json
	@# Add version incrementally (doesn't reprocess all existing versions)
	@echo "Adding to centralized schema structure..."
	@./scripts/centralize-schemas.sh add-version $(V)
	@# Update README matrix
	@./scripts/update-readme-matrix.sh
	@echo ""
	@echo "Created okapi-releases/$(V)/ with $$(ls -1 okapi-releases/$(V)/schemas/*.schema.json | wc -l | tr -d ' ') schemas"
	@echo ""
	@echo "Next steps:"
	@echo "  1. Review changes: git diff schema-versions.json README.md"
	@echo "  2. Commit: git add okapi-releases/$(V) schemas/ schema-versions.json README.md"
	@echo "  3. Push to trigger CI"

# Regenerate schemas for a single version
regenerate:
ifndef V
	$(error V is required. Usage: make regenerate V=1.47.0)
endif
	@if [ ! -d "okapi-releases/$(V)" ]; then \
		echo "Error: okapi-releases/$(V) does not exist. Use 'make add-release V=$(V)' first."; \
		exit 1; \
	fi
	@echo "Regenerating schemas for Okapi $(V)..."
	@rm -f okapi-releases/$(V)/schemas/*.schema.json
	@# Use version-specific pom if available, otherwise generate it
	@if [ ! -f "okapi-releases/$(V)/pom.xml" ]; then \
		echo "Generating pom.xml for Okapi $(V)..."; \
		./scripts/generate-version-pom.sh $(V); \
	fi
	@mvn -B -q compile -f okapi-releases/$(V)/pom.xml
	@mvn -B exec:java@generate-schemas -Dexec.args="okapi-releases/$(V)/schemas" -f okapi-releases/$(V)/pom.xml
	@echo '{"okapiVersion":"$(V)","generatedAt":"'$$(date -u +%Y-%m-%dT%H:%M:%SZ)'","filterCount":'$$(ls -1 okapi-releases/$(V)/schemas/*.schema.json 2>/dev/null | wc -l | tr -d ' ')'}' \
		| jq . > okapi-releases/$(V)/schemas/meta.json
	@echo "Regenerated $$(ls -1 okapi-releases/$(V)/schemas/*.schema.json | wc -l | tr -d ' ') schemas"
	@echo ""
	@echo "Run 'make version-schemas' to update schema versions"

# Generate pom.xml for a version (for existing versions without pom.xml)
generate-pom:
ifndef V
	$(error V is required. Usage: make generate-pom V=1.47.0)
endif
	@./scripts/generate-version-pom.sh $(V)

# Generate pom.xml files for all versions (always regenerates)
generate-all-poms:
	@echo "Generating pom.xml for all supported versions..."
	@for version in $(SUPPORTED_VERSIONS); do \
		./scripts/generate-version-pom.sh $$version; \
	done

# Regenerate schemas for all supported versions
regenerate-all:
	@echo "Regenerating schemas for all supported versions..."
	@for version in $(SUPPORTED_VERSIONS); do \
		echo ""; \
		echo "=== Okapi $$version ==="; \
		$(MAKE) regenerate V=$$version || exit 1; \
	done
	@echo ""
	@echo "All schemas regenerated. Run 'make version-schemas' to update versions."

# ============================================================================
# Versioning
# ============================================================================

# Recompute schema versions across all Okapi releases (uses centralize-schemas.sh)
version-schemas:
	@./scripts/centralize-schemas.sh

# ============================================================================
# Build
# ============================================================================

# Build tools (schema generator, Java 17+ — builds bridge-core first via reactor)
build-tools:
	@echo "Building schema generator tools (Java 17)..."
	@mvn -B compile -pl tools/schema-generator -am

# Build runtime JAR for a specific Okapi version
build:
ifndef V
	$(error V is required. Usage: make build V=1.47.0)
endif
	@echo "Building okapi-bridge for Okapi $(V)..."
	@# Read Java version from meta.json
	@java_version=$$(jq -r '.javaVersion // "11"' okapi-releases/$(V)/meta.json 2>/dev/null || echo "11"); \
	echo "Using Java $$java_version for Okapi $(V)"; \
	mvn -B package -f okapi-releases/$(V)/pom.xml -DskipTests

test:
	@echo "Running tests (bridge-core against Okapi $(LATEST_VERSION))..."
	@mvn -B test -pl bridge-core -Dokapi.version=$(LATEST_VERSION)

clean:
	@mvn -B clean 2>/dev/null || true
	@for version in $(SUPPORTED_VERSIONS); do \
		mvn -B clean -f okapi-releases/$$version/pom.xml 2>/dev/null || true; \
	done
	@rm -f .compile-generator

# ============================================================================
# Documentation
# ============================================================================

FILTER_DOCS_DIR := filter-docs

# Download filter documentation from Okapi wiki
download-filter-docs:
	@./scripts/download-filter-docs.sh $(FILTER_DOCS_DIR)

# Parse filter docs into structured JSON using Claude CLI
parse-filter-docs:
	@./scripts/parse-filter-docs.sh $(FILTER_DOCS_DIR)

# Force re-parse all filter docs (even if already parsed)
parse-filter-docs-force:
	@FORCE=1 ./scripts/parse-filter-docs.sh $(FILTER_DOCS_DIR)

# Bundle parsed docs into a single JSON file for UI consumption
bundle-filter-docs:
	@./scripts/bundle-filter-docs.sh $(FILTER_DOCS_DIR)

# Clean downloaded docs
clean-filter-docs:
	@rm -rf $(FILTER_DOCS_DIR)

# ============================================================================
# Utility
# ============================================================================

# Show differences between schema versions
diff-schemas:
ifndef V1
	$(error V1 and V2 are required. Usage: make diff-schemas V1=1.46.0 V2=1.47.0)
endif
ifndef V2
	$(error V1 and V2 are required. Usage: make diff-schemas V1=1.46.0 V2=1.47.0)
endif
	@echo "Comparing schemas between Okapi $(V1) and $(V2)..."
	@echo ""
	@echo "Filters in $(V1) but not in $(V2):"
	@comm -23 <(ls okapi-releases/$(V1)/schemas/*.schema.json 2>/dev/null | xargs -n1 basename | sort) \
	          <(ls okapi-releases/$(V2)/schemas/*.schema.json 2>/dev/null | xargs -n1 basename | sort) || true
	@echo ""
	@echo "Filters in $(V2) but not in $(V1):"
	@comm -13 <(ls okapi-releases/$(V1)/schemas/*.schema.json 2>/dev/null | xargs -n1 basename | sort) \
	          <(ls okapi-releases/$(V2)/schemas/*.schema.json 2>/dev/null | xargs -n1 basename | sort) || true
