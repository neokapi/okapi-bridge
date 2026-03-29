#!/bin/bash
# Centralize schemas from per-version directories
# Usage: centralize-schemas.sh [regenerate-composites]
#
# Operations:
#   (default)           - Full regeneration: generate bases and composites for all versions
#   regenerate-composites - Only regenerate composites from existing bases + overrides

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"
cd "$ROOT_DIR"

SCHEMAS_DIR="schemas"
BASE_DIR="$SCHEMAS_DIR/filters/base"
COMPOSITE_DIR="$SCHEMAS_DIR/filters/composite"
OVERRIDES_DIR="schemagen/overrides"
VERSIONS_FILE="schemas/versions.json"

# Get all Okapi versions sorted
get_versions() {
    ls -1 okapi-releases 2>/dev/null | sort -V
}

# Compute envelope Kind from filter ID: okf_html -> OkfHtmlFilterConfig
# PascalCases the format name (first letter uppercase, rest lowercase).
filter_to_kind() {
    local filter="$1"
    local format="${filter#okf_}"
    # PascalCase: first letter uppercase
    local pascal
    pascal="$(echo "${format:0:1}" | tr '[:lower:]' '[:upper:]')${format:1}"
    echo "Okf${pascal}FilterConfig"
}

# Initialize versions.json if needed
init_versions_file() {
    if [[ ! -f "$VERSIONS_FILE" ]]; then
        echo '{"$schema":"https://neokapi.dev/schemas/schema-versions.json","generatedAt":"","filters":{},"steps":{}}' > "$VERSIONS_FILE"
    fi
}

# Update generatedAt timestamp
update_timestamp() {
    local timestamp
    timestamp=$(date -u +%Y-%m-%dT%H:%M:%SZ)
    jq --arg ts "$timestamp" '.generatedAt = $ts' "$VERSIONS_FILE" > "$VERSIONS_FILE.tmp" && mv "$VERSIONS_FILE.tmp" "$VERSIONS_FILE"
}

# Get base version for a filter/hash combination
get_base_version() {
    local filter="$1"
    local base_hash="$2"
    jq -r --arg f "$filter" --arg h "$base_hash" '
        .filters[$f].versions[]? | select(.baseHash == $h) | .baseVersion // empty
    ' "$VERSIONS_FILE" | head -1
}

# Get next base version number for a filter
get_next_base_version() {
    local filter="$1"
    jq -r --arg f "$filter" '
        (.filters[$f].versions // []) | map(.baseVersion // 0) | max // 0 | . + 1
    ' "$VERSIONS_FILE"
}

# Get composite version for a filter/hash combination
get_composite_version() {
    local filter="$1"
    local composite_hash="$2"
    jq -r --arg f "$filter" --arg h "$composite_hash" '
        .filters[$f].versions[]? | select(.compositeHash == $h) | .version // empty
    ' "$VERSIONS_FILE"
}

# Get next version number for a filter
get_next_version() {
    local filter="$1"
    jq -r --arg f "$filter" '
        (.filters[$f].versions // []) | map(.version) | max // 0 | . + 1
    ' "$VERSIONS_FILE"
}

# Add or update version entry in schema-versions.json
update_version_entry() {
    local filter="$1"
    local version="$2"
    local base_version="$3"
    local base_hash="$4"
    local override_hash="$5"
    local composite_hash="$6"
    local okapi_version="$7"
    local introduced_in="$8"
    
    # Check if this version already exists
    local existing
    existing=$(jq -r --arg f "$filter" --arg v "$version" '
        .filters[$f].versions[]? | select(.version == ($v | tonumber)) | .version // empty
    ' "$VERSIONS_FILE")
    
    if [[ -n "$existing" ]]; then
        # Add okapi version to existing entry
        jq --arg f "$filter" --arg v "$version" --arg ov "$okapi_version" '
            .filters[$f].versions |= map(
                if .version == ($v | tonumber) then
                    .okapiVersions |= (. + [$ov] | unique)
                else .
                end
            )
        ' "$VERSIONS_FILE" > "$VERSIONS_FILE.tmp" && mv "$VERSIONS_FILE.tmp" "$VERSIONS_FILE"
    else
        # Create new version entry
        local override_json="null"
        if [[ -n "$override_hash" ]]; then
            override_json="\"$override_hash\""
        fi

        local kind
        kind=$(filter_to_kind "$filter")
        local api_version="v${version}"

        jq --arg f "$filter" \
           --argjson v "$version" \
           --argjson bv "$base_version" \
           --arg bh "$base_hash" \
           --argjson oh "$override_json" \
           --arg ch "$composite_hash" \
           --arg ov "$okapi_version" \
           --arg intro "$introduced_in" \
           --arg k "$kind" \
           --arg av "$api_version" '
            .filters[$f].versions //= [] |
            .filters[$f].versions += [{
                version: $v,
                baseVersion: $bv,
                baseHash: $bh,
                overrideHash: $oh,
                compositeHash: $ch,
                kind: $k,
                apiVersion: $av,
                introducedInOkapi: $intro,
                okapiVersions: [$ov]
            }]
        ' "$VERSIONS_FILE" > "$VERSIONS_FILE.tmp" && mv "$VERSIONS_FILE.tmp" "$VERSIONS_FILE"
    fi
}

# Process a single base schema for an Okapi version
process_schema() {
    local okapi_version="$1"
    local base_file="$2"
    
    local filename
    filename=$(basename "$base_file")
    local filter="${filename%.schema.json}"
    
    # Compute base hash
    local base_hash
    base_hash=$("$SCRIPT_DIR/compute-hash.sh" "$base_file")
    
    # Check for override
    local override_file="$OVERRIDES_DIR/${filter}.overrides.json"
    local override_hash=""
    if [[ -f "$override_file" ]]; then
        override_hash=$("$SCRIPT_DIR/compute-hash.sh" "$override_file")
    fi
    
    # Generate composite
    local tmp_composite="/tmp/${filter}.composite.json"
    "$SCRIPT_DIR/merge-schema.sh" "$base_file" "$override_file" "$tmp_composite" 2>/dev/null || \
        cp "$base_file" "$tmp_composite"
    
    # Compute composite hash
    local composite_hash
    composite_hash=$("$SCRIPT_DIR/compute-hash.sh" "$tmp_composite")
    
    # Check if this composite already exists
    local existing_version
    existing_version=$(get_composite_version "$filter" "$composite_hash")
    
    if [[ -n "$existing_version" ]]; then
        # Same composite - just add Okapi version to existing
        local existing_base_version
        existing_base_version=$(get_base_version "$filter" "$base_hash")
        update_version_entry "$filter" "$existing_version" "$existing_base_version" "$base_hash" "$override_hash" "$composite_hash" "$okapi_version" ""
        echo "  = $filter v$existing_version (unchanged)"
        rm -f "$tmp_composite"
        return
    fi
    
    # New composite version needed
    local new_version
    new_version=$(get_next_version "$filter")
    
    # Check if this base already exists or needs a new version
    local base_version
    base_version=$(get_base_version "$filter" "$base_hash")
    if [[ -z "$base_version" ]]; then
        base_version=$(get_next_base_version "$filter")
    fi
    
    # Save base schema (versioned naming)
    local base_output="$BASE_DIR/${filter}.v${base_version}.schema.json"
    if [[ ! -f "$base_output" ]]; then
        cp "$base_file" "$base_output"
    fi
    
    # Save composite schema
    local composite_output="$COMPOSITE_DIR/${filter}.v${new_version}.schema.json"
    
    local kind
    kind=$(filter_to_kind "$filter")
    local api_version="v${new_version}"

    # Add version metadata to composite (reorder so $schema, $id, version come first)
    jq --argjson v "$new_version" \
       --argjson bv "$base_version" \
       --arg intro "$okapi_version" \
       --arg bh "$base_hash" \
       --arg ch "$composite_hash" \
       --arg f "$filter" \
       --arg k "$kind" \
       --arg av "$api_version" '
        {
            "$schema": ."$schema",
            "$id": "https://neokapi.github.io/schemas/filters/\($f).v\($v).schema.json",
            "version": $v
        } + del(."$schema", ."$id", ."$version", .["x-schemaVersion"]) + {
            "x-kind": $k,
            "x-apiVersion": $av,
            "x-baseVersion": $bv,
            "x-introducedInOkapi": $intro,
            "x-baseHash": $bh,
            "x-compositeHash": $ch
        }
    ' "$tmp_composite" > "$composite_output"

    rm -f "$tmp_composite"

    # Update versions file
    update_version_entry "$filter" "$new_version" "$base_version" "$base_hash" "$override_hash" "$composite_hash" "$okapi_version" "$okapi_version"

    if [[ "$new_version" -eq 1 ]]; then
        echo "  + $filter v1 (new)"
    else
        echo "  ↑ $filter v$new_version (changed)"
    fi
}

# Main: regenerate all schemas
regenerate_all() {
    echo "Centralizing schemas..."
    
    # Create directories
    mkdir -p "$BASE_DIR" "$COMPOSITE_DIR"
    
    # Initialize versions file
    init_versions_file
    
    # Process each Okapi version
    for version in $(get_versions); do
        local schemas_dir="okapi-releases/$version/schemas"
        
        if [[ ! -d "$schemas_dir" ]]; then
            echo "=== Okapi $version ==="
            echo "  Generating base schemas..."
            
            # Generate schemas using Java
            mkdir -p "$schemas_dir"
            if [[ -f "okapi-releases/$version/pom.xml" ]]; then
                mvn -B -q compile -f "okapi-releases/$version/pom.xml" 2>/dev/null || true
                mvn -B -q exec:java@generate-schemas -Dexec.args="$schemas_dir" -f "okapi-releases/$version/pom.xml" 2>/dev/null || true
            fi
        fi
        
        if [[ ! -d "$schemas_dir" ]] || [[ -z "$(ls -A "$schemas_dir"/*.schema.json 2>/dev/null)" ]]; then
            echo "=== Okapi $version ==="
            echo "  No schemas found, skipping"
            continue
        fi
        
        echo "=== Okapi $version ==="
        
        for schema_file in "$schemas_dir"/*.schema.json; do
            [[ -f "$schema_file" ]] || continue
            [[ "$(basename "$schema_file")" == "meta.json" ]] && continue
            process_schema "$version" "$schema_file"
        done
    done
    
    # Update timestamp
    update_timestamp
    
    echo ""
    echo "=== Summary ==="
    echo "Base schemas: $(ls -1 "$BASE_DIR" 2>/dev/null | wc -l | tr -d ' ')"
    echo "Composite schemas: $(ls -1 "$COMPOSITE_DIR" 2>/dev/null | wc -l | tr -d ' ')"
    echo "Filters: $(jq '.filters | length' "$VERSIONS_FILE")"
}

# Regenerate composites only (when overrides change)
# Uses existing bases in schemas/filters/base/ and re-merges with current overrides
# Derives okapiVersions from the actual per-version source schemas (ground truth)
regenerate_composites() {
    echo "Regenerating composite schemas from existing bases..."

    if [[ ! -d "$BASE_DIR" ]]; then
        echo "Error: No base schemas found. Run full regeneration first." >&2
        exit 1
    fi

    # Build authoritative baseHash -> okapiVersions map from per-version source schemas
    # This is the ground truth: each Okapi version produces exactly one base hash per filter
    local hash_map_file="/tmp/schema-hash-map.json"
    echo '{}' > "$hash_map_file"

    echo "Building base hash map from source schemas..."
    for okapi_version in $(get_versions); do
        local schemas_dir="okapi-releases/$okapi_version/schemas"
        [[ -d "$schemas_dir" ]] || continue

        for schema_file in "$schemas_dir"/*.schema.json; do
            [[ -f "$schema_file" ]] || continue
            [[ "$(basename "$schema_file")" == "meta.json" ]] && continue

            local filter
            filter="$(basename "$schema_file" .schema.json)"
            local base_hash
            base_hash=$("$SCRIPT_DIR/compute-hash.sh" "$schema_file")
            local key="${filter}:${base_hash}"

            jq --arg k "$key" --arg ov "$okapi_version" '
                .[$k] //= {okapiVersions: []} |
                .[$k].okapiVersions += [$ov] |
                .[$k].okapiVersions |= unique
            ' "$hash_map_file" > "$hash_map_file.tmp" && mv "$hash_map_file.tmp" "$hash_map_file"
        done
    done

    # Also build set of base hashes that are actually in use
    local active_bases_file="/tmp/schema-active-bases.json"
    jq '[to_entries[] | .key | split(":") | .[1]] | unique' "$hash_map_file" > "$active_bases_file"

    # Clear existing composites
    rm -f "$COMPOSITE_DIR"/*.schema.json

    # Reset composite versions but keep structure
    jq '.filters |= with_entries(.value.versions = [])' "$VERSIONS_FILE" > "$VERSIONS_FILE.tmp" && mv "$VERSIONS_FILE.tmp" "$VERSIONS_FILE"

    # Get unique filters from base directory
    local filters
    filters=$(ls "$BASE_DIR"/*.schema.json 2>/dev/null | sed 's/.*\///' | sed 's/\.v[0-9]*\.schema\.json//' | sort -u)

    for filter in $filters; do
        echo "Processing $filter..."

        # Find all base versions for this filter
        local base_files
        base_files=$(ls "$BASE_DIR/${filter}".v*.schema.json 2>/dev/null | \
            sed 's/.*\.v\([0-9]*\)\.schema\.json$/\1 &/' | sort -n | awk '{print $2}')

        local override_file="$OVERRIDES_DIR/${filter}.overrides.json"
        local override_hash=""
        if [[ -f "$override_file" ]]; then
            override_hash=$("$SCRIPT_DIR/compute-hash.sh" "$override_file")
        fi

        local composite_version=0
        for base_file in $base_files; do
            # Extract base version from filename
            local base_version
            base_version=$(basename "$base_file" | sed 's/.*\.v\([0-9]*\)\.schema\.json/\1/')

            local base_hash
            base_hash=$("$SCRIPT_DIR/compute-hash.sh" "$base_file")

            # Skip orphaned base schemas (no Okapi version produces this hash)
            local hash_key="${filter}:${base_hash}"
            local okapi_versions
            okapi_versions=$(jq -c --arg k "$hash_key" '.[$k].okapiVersions // []' "$hash_map_file")
            if [[ "$okapi_versions" == "[]" ]]; then
                echo "  - $filter base v$base_version (orphaned, skipping)"
                continue
            fi

            # Generate composite
            local tmp_composite="/tmp/${filter}.composite.json"
            "$SCRIPT_DIR/merge-schema.sh" "$base_file" "$override_file" "$tmp_composite" 2>/dev/null || \
                cp "$base_file" "$tmp_composite"

            local composite_hash
            composite_hash=$("$SCRIPT_DIR/compute-hash.sh" "$tmp_composite")

            # Check if we already have a composite with this hash
            local existing
            existing=$(get_composite_version "$filter" "$composite_hash")

            if [[ -n "$existing" ]]; then
                # Merge okapiVersions into existing entry
                jq --arg f "$filter" --arg ch "$composite_hash" --argjson ov "$okapi_versions" '
                    .filters[$f].versions |= map(
                        if .compositeHash == $ch then
                            .okapiVersions |= (. + $ov | unique)
                        else .
                        end
                    )
                ' "$VERSIONS_FILE" > "$VERSIONS_FILE.tmp" && mv "$VERSIONS_FILE.tmp" "$VERSIONS_FILE"
                echo "  = $filter base v$base_version -> composite v$existing (same hash)"
                rm -f "$tmp_composite"
                continue
            fi

            # New composite version
            composite_version=$((composite_version + 1))
            local composite_output="$COMPOSITE_DIR/${filter}.v${composite_version}.schema.json"

            # Determine introducedInOkapi (earliest Okapi version using this base)
            local introduced_in
            introduced_in=$(echo "$okapi_versions" | jq -r 'sort_by(split(".") | map(tonumber? // .)) | .[0] // null')

            local kind
            kind=$(filter_to_kind "$filter")
            local api_version="v${composite_version}"

            # Add version metadata (reorder so $schema, $id, version come first)
            jq --argjson v "$composite_version" \
               --argjson bv "$base_version" \
               --arg bh "$base_hash" \
               --arg ch "$composite_hash" \
               --arg intro "$introduced_in" \
               --arg f "$filter" \
               --arg k "$kind" \
               --arg av "$api_version" '
                {
                    "$schema": ."$schema",
                    "$id": "https://neokapi.github.io/schemas/filters/\($f).v\($v).schema.json",
                    "version": $v
                } + del(."$schema", ."$id", ."$version", .["x-schemaVersion"]) + {
                    "x-kind": $k,
                    "x-apiVersion": $av,
                    "x-baseVersion": $bv,
                    "x-introducedInOkapi": $intro,
                    "x-baseHash": $bh,
                    "x-compositeHash": $ch
                } | if .["x-introducedInOkapi"] == "null" then del(.["x-introducedInOkapi"]) else . end
            ' "$tmp_composite" > "$composite_output"

            rm -f "$tmp_composite"

            # Update versions file
            local override_json="null"
            if [[ -n "$override_hash" ]]; then
                override_json="\"$override_hash\""
            fi

            jq --arg f "$filter" \
               --argjson v "$composite_version" \
               --argjson bv "$base_version" \
               --arg bh "$base_hash" \
               --argjson oh "$override_json" \
               --arg ch "$composite_hash" \
               --argjson ov "$okapi_versions" \
               --arg intro "$introduced_in" \
               --arg k "$kind" \
               --arg av "$api_version" '
                .filters[$f].versions += [{
                    version: $v,
                    baseVersion: $bv,
                    baseHash: $bh,
                    overrideHash: $oh,
                    compositeHash: $ch,
                    kind: $k,
                    apiVersion: $av,
                    okapiVersions: $ov
                } + (if $intro != "null" then {introducedInOkapi: $intro} else {} end)]
            ' "$VERSIONS_FILE" > "$VERSIONS_FILE.tmp" && mv "$VERSIONS_FILE.tmp" "$VERSIONS_FILE"

            echo "  + $filter base v$base_version -> composite v$composite_version"
        done
    done

    # Cleanup
    rm -f "$hash_map_file" "$active_bases_file"

    # Update timestamp
    update_timestamp

    echo ""
    echo "Composite regeneration complete."
    echo "Composites: $(ls -1 "$COMPOSITE_DIR" 2>/dev/null | wc -l | tr -d ' ')"
}

# Add a single new Okapi version incrementally (doesn't reprocess existing versions)
add_version() {
    local version="$1"
    local schemas_dir="okapi-releases/$version/schemas"
    
    if [[ ! -d "$schemas_dir" ]]; then
        echo "Error: $schemas_dir does not exist" >&2
        exit 1
    fi
    
    echo "Adding Okapi $version to centralized schemas..."
    
    # Ensure directories exist
    mkdir -p "$BASE_DIR" "$COMPOSITE_DIR"
    
    # Initialize versions file if needed
    init_versions_file
    
    local new_bases=0
    local updated=0
    
    for schema_file in "$schemas_dir"/*.schema.json; do
        [[ -f "$schema_file" ]] || continue
        [[ "$(basename "$schema_file")" == "meta.json" ]] && continue
        
        local filename
        filename=$(basename "$schema_file")
        local filter="${filename%.schema.json}"
        
        # Compute hash of new schema
        local base_hash
        base_hash=$("$SCRIPT_DIR/compute-hash.sh" "$schema_file")
        
        # Check if this hash already exists as a base
        local existing_base_version
        existing_base_version=$(get_base_version "$filter" "$base_hash")
        
        if [[ -n "$existing_base_version" ]]; then
            # Same base content - compute composite hash to find the right entry
            local override_file="$OVERRIDES_DIR/${filter}.overrides.json"
            local base_output="$BASE_DIR/${filter}.v${existing_base_version}.schema.json"
            local tmp_composite="/tmp/${filter}.composite.json"
            "$SCRIPT_DIR/merge-schema.sh" "$base_output" "$override_file" "$tmp_composite" 2>/dev/null || \
                cp "$base_output" "$tmp_composite"
            local composite_hash
            composite_hash=$("$SCRIPT_DIR/compute-hash.sh" "$tmp_composite")
            rm -f "$tmp_composite"

            # Only add Okapi version to the entry with matching composite hash
            jq --arg f "$filter" --arg ch "$composite_hash" --arg ov "$version" '
                .filters[$f].versions |= map(
                    if .compositeHash == $ch then
                        .okapiVersions |= (. + [$ov] | unique | sort_by(. | split(".") | map(tonumber? // .)))
                    else .
                    end
                )
            ' "$VERSIONS_FILE" > "$VERSIONS_FILE.tmp" && mv "$VERSIONS_FILE.tmp" "$VERSIONS_FILE"

            echo "  = $filter (base v$existing_base_version, added $version)"
            updated=$((updated + 1))
        else
            # New base schema needed
            local base_version
            base_version=$(get_next_base_version "$filter")
            
            # Save base schema
            local base_output="$BASE_DIR/${filter}.v${base_version}.schema.json"
            cp "$schema_file" "$base_output"
            
            # Check for override
            local override_file="$OVERRIDES_DIR/${filter}.overrides.json"
            local override_hash=""
            if [[ -f "$override_file" ]]; then
                override_hash=$("$SCRIPT_DIR/compute-hash.sh" "$override_file")
            fi
            
            # Generate composite
            local tmp_composite="/tmp/${filter}.composite.json"
            "$SCRIPT_DIR/merge-schema.sh" "$base_output" "$override_file" "$tmp_composite" 2>/dev/null || \
                cp "$base_output" "$tmp_composite"
            
            local composite_hash
            composite_hash=$("$SCRIPT_DIR/compute-hash.sh" "$tmp_composite")
            
            # Get next composite version
            local composite_version
            composite_version=$(get_next_version "$filter")
            
            local kind
            kind=$(filter_to_kind "$filter")
            local api_version="v${composite_version}"

            # Save composite with metadata
            local composite_output="$COMPOSITE_DIR/${filter}.v${composite_version}.schema.json"
            jq --argjson v "$composite_version" \
               --argjson bv "$base_version" \
               --arg intro "$version" \
               --arg bh "$base_hash" \
               --arg ch "$composite_hash" \
               --arg f "$filter" \
               --arg k "$kind" \
               --arg av "$api_version" '
                {
                    "$schema": ."$schema",
                    "$id": "https://neokapi.github.io/schemas/filters/\($f).v\($v).schema.json",
                    "version": $v
                } + del(."$schema", ."$id", ."$version", .["x-schemaVersion"]) + {
                    "x-kind": $k,
                    "x-apiVersion": $av,
                    "x-baseVersion": $bv,
                    "x-introducedInOkapi": $intro,
                    "x-baseHash": $bh,
                    "x-compositeHash": $ch
                }
            ' "$tmp_composite" > "$composite_output"

            rm -f "$tmp_composite"

            # Add to versions file
            local override_json="null"
            if [[ -n "$override_hash" ]]; then
                override_json="\"$override_hash\""
            fi

            jq --arg f "$filter" \
               --argjson v "$composite_version" \
               --argjson bv "$base_version" \
               --arg bh "$base_hash" \
               --argjson oh "$override_json" \
               --arg ch "$composite_hash" \
               --arg ov "$version" \
               --arg intro "$version" \
               --arg k "$kind" \
               --arg av "$api_version" '
                .filters[$f] //= {versions: []} |
                .filters[$f].versions += [{
                    version: $v,
                    baseVersion: $bv,
                    baseHash: $bh,
                    overrideHash: $oh,
                    compositeHash: $ch,
                    kind: $k,
                    apiVersion: $av,
                    introducedInOkapi: $intro,
                    okapiVersions: [$ov]
                }]
            ' "$VERSIONS_FILE" > "$VERSIONS_FILE.tmp" && mv "$VERSIONS_FILE.tmp" "$VERSIONS_FILE"

            echo "  + $filter v$composite_version (new in $version)"
            new_bases=$((new_bases + 1))
        fi
    done
    
    # Update timestamp
    update_timestamp
    
    echo ""
    echo "Added Okapi $version: $new_bases new schema versions, $updated unchanged"
}

# ============================================================================
# Step Schema Versioning
# ============================================================================

STEPS_BASE_DIR="$SCHEMAS_DIR/steps/base"

# Get step version for a step/hash combination
get_step_version() {
    local step_id="$1"
    local hash="$2"
    jq -r --arg s "$step_id" --arg h "$hash" '
        .steps[$s].versions[]? | select(.hash == $h) | .version // empty
    ' "$VERSIONS_FILE" | head -1
}

# Get next step version number
get_next_step_version() {
    local step_id="$1"
    jq -r --arg s "$step_id" '
        (.steps[$s].versions // []) | map(.version) | max // 0 | . + 1
    ' "$VERSIONS_FILE"
}

# Add a single Okapi version's step schemas incrementally
add_step_version() {
    local version="$1"
    local steps_dir="okapi-releases/$version/schemas/steps"

    if [[ ! -d "$steps_dir" ]]; then
        echo "No step schemas found for Okapi $version, skipping step versioning"
        return
    fi

    echo "Adding step schemas for Okapi $version..."

    # Ensure directories and steps key exist
    mkdir -p "$STEPS_BASE_DIR"

    # Initialize steps key in versions.json if missing
    if ! jq -e '.steps' "$VERSIONS_FILE" > /dev/null 2>&1; then
        jq '. + {steps: {}}' "$VERSIONS_FILE" > "$VERSIONS_FILE.tmp" && mv "$VERSIONS_FILE.tmp" "$VERSIONS_FILE"
    fi

    local new_steps=0
    local updated=0

    for schema_file in "$steps_dir"/*.schema.json; do
        [[ -f "$schema_file" ]] || continue

        local filename
        filename=$(basename "$schema_file")
        local step_id="${filename%.schema.json}"

        # Compute hash of schema content
        local hash
        hash=$("$SCRIPT_DIR/compute-hash.sh" "$schema_file")

        # Check if this hash already exists
        local existing_version
        existing_version=$(get_step_version "$step_id" "$hash")

        if [[ -n "$existing_version" ]]; then
            # Same content — just add Okapi version to existing entry
            jq --arg s "$step_id" --arg h "$hash" --arg ov "$version" '
                .steps[$s].versions |= map(
                    if .hash == $h then
                        .okapiVersions |= (. + [$ov] | unique | sort_by(. | split(".") | map(tonumber? // .)))
                    else .
                    end
                )
            ' "$VERSIONS_FILE" > "$VERSIONS_FILE.tmp" && mv "$VERSIONS_FILE.tmp" "$VERSIONS_FILE"

            echo "  = $step_id v$existing_version (unchanged, added $version)"
            updated=$((updated + 1))
        else
            # New version needed
            local new_version
            new_version=$(get_next_step_version "$step_id")

            # Copy to versioned base location
            local base_output="$STEPS_BASE_DIR/${step_id}.v${new_version}.schema.json"
            cp "$schema_file" "$base_output"

            # Add to versions.json
            jq --arg s "$step_id" \
               --argjson v "$new_version" \
               --arg h "$hash" \
               --arg ov "$version" \
               --arg intro "$version" '
                .steps[$s] //= {versions: []} |
                .steps[$s].versions += [{
                    version: $v,
                    hash: $h,
                    introducedInOkapi: $intro,
                    okapiVersions: [$ov]
                }]
            ' "$VERSIONS_FILE" > "$VERSIONS_FILE.tmp" && mv "$VERSIONS_FILE.tmp" "$VERSIONS_FILE"

            echo "  + $step_id v$new_version (new in $version)"
            new_steps=$((new_steps + 1))
        fi
    done

    echo "Step schemas for Okapi $version: $new_steps new, $updated unchanged"
}

# Parse command
case "${1:-}" in
    regenerate-composites)
        regenerate_composites
        ;;
    add-version)
        if [[ -z "${2:-}" ]]; then
            echo "Usage: $0 add-version <okapi-version>" >&2
            exit 1
        fi
        add_version "$2"
        ;;
    add-step-version)
        if [[ -z "${2:-}" ]]; then
            echo "Usage: $0 add-step-version <okapi-version>" >&2
            exit 1
        fi
        add_step_version "$2"
        ;;
    *)
        regenerate_all
        ;;
esac
