# Okapi Bridge Makefile
# Manages schema generation across Okapi versions with Java version separation

SHELL := /bin/bash
.PHONY: help list-upstream list-local add-release regenerate regenerate-all \
        version-schemas build clean test generate-pom generate-all-poms \
        centralize regenerate-composites build-tools \
        download-filter-docs parse-filter-docs parse-filter-docs-force \
        bundle-filter-docs clean-filter-docs snapshot \
        verify-schemas update-readme-matrix \
        release release-prepare release-perform

# Configuration - derived from okapi-releases directory
SUPPORTED_VERSIONS := $(shell ls -1 okapi-releases 2>/dev/null | sort -V)
LATEST_VERSION := $(shell ls -1 okapi-releases 2>/dev/null | sort -V | tail -1)
VERSIONS_FILE := schemas/versions.json

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
	@echo "  make verify-schemas       Verify schemas and README are up to date (same as CI)"
	@echo "  make update-readme-matrix Update README schema matrix"
	@echo "  make add-release V=1.48.0 Add new Okapi version"
	@echo ""
	@echo "Build:"
	@echo "  make build V=1.47.0       Build JAR for specific Okapi version"
	@echo "  make snapshot V=1.49.0-SNAPSHOT  Build against locally-installed Okapi"
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
	@echo "Release:"
	@echo "  make release              Release current version (bump, tag, push, snapshot)"
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

# Update README schema matrix
update-readme-matrix:
	@./scripts/update-readme-matrix.sh

# Verify schemas and README are up to date (mirrors CI verify-schemas job)
verify-schemas: regenerate-composites update-readme-matrix
	@echo "Checking for uncommitted schema or README changes..."
	@git diff --stat -- schemas/ README.md
	@DIFF=$$(git diff -- schemas/ README.md | grep '^[+-]' | grep -v '^[+-][+-][+-]' | grep -v '"generatedAt"') || true; \
	if [ -n "$$DIFF" ]; then \
		echo ""; \
		echo "ERROR: Composite schemas or README are out of date."; \
		echo "The regenerated files differ from what is committed."; \
		echo ""; \
		echo "$$DIFF" | head -40; \
		echo ""; \
		echo "Review the changes and commit them."; \
		exit 1; \
	fi
	@echo "Schemas and README are up to date."

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
	@# Add filter version incrementally (doesn't reprocess all existing versions)
	@echo "Adding to centralized schema structure..."
	@./scripts/centralize-schemas.sh add-version $(V)
	@# Add step version incrementally
	@echo "Adding step schemas to centralized structure..."
	@./scripts/centralize-schemas.sh add-step-version $(V)
	@# Update README matrix
	@./scripts/update-readme-matrix.sh
	@echo ""
	@echo "Created okapi-releases/$(V)/ with $$(ls -1 okapi-releases/$(V)/schemas/*.schema.json | wc -l | tr -d ' ') schemas"
	@echo ""
	@echo "Next steps:"
	@echo "  1. Review changes: git diff schemas/versions.json README.md"
	@echo "  2. Commit: git add okapi-releases/$(V) schemas/ schemagen/ README.md"
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

# Build against locally-installed Okapi (e.g. from source)
snapshot:
ifndef V
	$(error V is required. Usage: make snapshot V=1.49.0-SNAPSHOT)
endif
	@echo "Building against local Okapi $(V)..."
	@mvn -B install -N -DskipTests -q
	@SNAPSHOT_DIR=$$(mktemp -d) && \
	./scripts/generate-version-pom.sh --local --output-dir "$$SNAPSHOT_DIR" $(V) && \
	mvn -B package -f "$$SNAPSHOT_DIR/pom.xml" -DskipTests && \
	echo "" && \
	echo "Built: $$(ls $$SNAPSHOT_DIR/target/neokapi-bridge-*-jar-with-dependencies.jar)" && \
	echo "Test:  mvn -B test -pl bridge-core -Dokapi.version=$(V)"

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

# ============================================================================
# Release
# ============================================================================

# Current version from pom.xml (strips -SNAPSHOT if present)
CURRENT_VERSION := $(shell mvn help:evaluate -Dexpression=project.version -q -DforceStdout 2>/dev/null | sed 's/-SNAPSHOT//')

# Release: bump to release version, commit, tag, push, then bump to next snapshot.
# Usage: make release
#   Reads the current version from pom.xml, strips -SNAPSHOT, creates a release
#   commit + tag, pushes, then bumps to next minor-SNAPSHOT and pushes again.
release:
	@if [ -z "$(CURRENT_VERSION)" ]; then echo "Error: could not read version from pom.xml"; exit 1; fi
	@echo "Releasing v$(CURRENT_VERSION)..."
	@# Ensure clean working tree
	@if [ -n "$$(git status --porcelain -- pom.xml bridge-core/pom.xml)" ]; then \
		echo "Error: pom.xml has uncommitted changes"; exit 1; \
	fi
	@# Set release version (strip -SNAPSHOT)
	mvn versions:set -DnewVersion=$(CURRENT_VERSION) -DgenerateBackupFiles=false -q
	@# Commit and tag
	git add pom.xml bridge-core/pom.xml
	git commit -m "release: v$(CURRENT_VERSION)"
	git tag -a "v$(CURRENT_VERSION)" -m "Release v$(CURRENT_VERSION)"
	@# Push commit + tag
	git push origin main
	git push origin "v$(CURRENT_VERSION)"
	@echo "Tagged and pushed v$(CURRENT_VERSION)"
	@# Bump to next snapshot
	mvn versions:set -DnewVersion=$(CURRENT_VERSION) -DgenerateBackupFiles=false -q
	@# Increment minor version for next development cycle
	$(eval NEXT := $(shell echo $(CURRENT_VERSION) | awk -F. '{print $$1"."$$2+1".0"}'))
	mvn versions:set -DnewVersion=$(NEXT)-SNAPSHOT -DgenerateBackupFiles=false -q
	git add pom.xml bridge-core/pom.xml
	git commit -m "chore: bump to $(NEXT)-SNAPSHOT for next development cycle"
	git push origin main
	@echo "Bumped to $(NEXT)-SNAPSHOT"
	@echo ""
	@echo "Release complete: v$(CURRENT_VERSION)"
	@echo "  Tag: v$(CURRENT_VERSION)"
	@echo "  Next dev: $(NEXT)-SNAPSHOT"
