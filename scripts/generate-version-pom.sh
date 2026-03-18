#!/bin/bash
# Generate pom.xml for a specific Okapi version by discovering available filters.
#
# The generated pom inherits from the parent (okapi-bridge-parent) which provides:
#   - All infrastructure dependencies (gRPC, Gson, SnakeYAML, etc.)
#   - Plugin configuration (protobuf, shade, exec, build-helper)
#
# The generated pom only declares:
#   - Parent reference
#   - Okapi version + Java version properties
#   - Version-specific Okapi filter dependencies
#   - Build section with source paths and plugin references
#
# Usage: ./scripts/generate-version-pom.sh [--local] [--output-dir DIR] <version>
#
# Options:
#   --local         Discover filters from local Maven repo (~/.m2) instead of Maven Central
#   --output-dir    Write pom.xml to a custom directory (uses absolute path references)

set -e

REPO_ROOT=$(cd "$(dirname "$0")/.." && pwd)

# Parse flags
LOCAL_MODE=false
CUSTOM_OUTPUT_DIR=""
while [[ $# -gt 0 ]]; do
    case "$1" in
        --local)
            LOCAL_MODE=true
            shift
            ;;
        --output-dir)
            CUSTOM_OUTPUT_DIR="$2"
            shift 2
            ;;
        -*)
            echo "Unknown option: $1"
            exit 1
            ;;
        *)
            VERSION="$1"
            shift
            ;;
    esac
done

if [ -z "$VERSION" ]; then
    echo "Usage: $0 [--local] [--output-dir DIR] <okapi-version>"
    exit 1
fi

if [ -n "$CUSTOM_OUTPUT_DIR" ]; then
    OUTPUT_DIR="$CUSTOM_OUTPUT_DIR"
    PATH_PREFIX="$REPO_ROOT/"
else
    OUTPUT_DIR="okapi-releases/$VERSION"
    PATH_PREFIX="../../"
fi
OUTPUT_FILE="$OUTPUT_DIR/pom.xml"
META_FILE="okapi-releases/$VERSION/meta.json"

# Read Java version from meta.json if it exists, default to 11
JAVA_VERSION="11"
if [ -f "$META_FILE" ]; then
    META_JAVA_VERSION=$(jq -r '.javaVersion // "11"' "$META_FILE" 2>/dev/null)
    if [ -n "$META_JAVA_VERSION" ] && [ "$META_JAVA_VERSION" != "null" ]; then
        JAVA_VERSION="$META_JAVA_VERSION"
    fi
fi
echo "Java version for Okapi $VERSION: $JAVA_VERSION"

# Known filter artifact IDs to check
KNOWN_FILTERS=(
    okapi-filter-abstractmarkup
    okapi-filter-archive
    okapi-filter-autoxliff
    okapi-filter-cascadingfilter
    okapi-filter-doxygen
    okapi-filter-dtd
    okapi-filter-epub
    okapi-filter-html
    okapi-filter-icml
    okapi-filter-idml
    okapi-filter-its
    okapi-filter-json
    okapi-filter-markdown
    okapi-filter-messageformat
    okapi-filter-mif
    okapi-filter-mosestext
    okapi-filter-multiparsers
    okapi-filter-openoffice
    okapi-filter-openxml
    okapi-filter-pdf
    okapi-filter-pensieve
    okapi-filter-php
    okapi-filter-plaintext
    okapi-filter-po
    okapi-filter-properties
    okapi-filter-rainbowkit
    okapi-filter-regex
    okapi-filter-rtf
    okapi-filter-sdlpackage
    okapi-filter-subtitles
    okapi-filter-table
    okapi-filter-tex
    okapi-filter-tmx
    okapi-filter-transifex
    okapi-filter-transtable
    okapi-filter-ts
    okapi-filter-ttx
    okapi-filter-txml
    okapi-filter-vignette
    okapi-filter-wiki
    okapi-filter-wsxzpackage
    okapi-filter-xini
    okapi-filter-xliff
    okapi-filter-xliff2
    okapi-filter-xmlstream
    okapi-filter-yaml
)

# Read bridge version from root pom.xml
BRIDGE_VERSION=$(sed -n '/<artifactId>okapi-bridge-parent<\/artifactId>/{n;s/.*<version>\(.*\)<\/version>.*/\1/p;}' "$(dirname "$0")/../pom.xml")
if [ -z "$BRIDGE_VERSION" ]; then
    echo "Error: could not read version from root pom.xml"
    exit 1
fi
echo "Bridge version: $BRIDGE_VERSION"

echo "Discovering available filters for Okapi $VERSION..."

# Create a temp file for results
TEMP_RESULTS=$(mktemp)

# Function to check a single filter via Maven Central
check_filter() {
    local filter=$1
    local version=$2
    local url="https://repo1.maven.org/maven2/net/sf/okapi/filters/$filter/$version/$filter-$version.pom"
    if curl --output /dev/null --silent --head --fail --max-time 5 "$url" 2>/dev/null; then
        echo "$filter"
    fi
}

# Function to check a single filter in local Maven repo (~/.m2)
check_filter_local() {
    local filter=$1
    local version=$2
    if [ -f "$HOME/.m2/repository/net/sf/okapi/filters/$filter/$version/$filter-$version.jar" ]; then
        echo "$filter"
    fi
}

if [ "$LOCAL_MODE" = true ]; then
    export -f check_filter_local
    # Check filters in parallel (up to 10 at a time)
    for filter in "${KNOWN_FILTERS[@]}"; do
        echo "$filter"
    done | xargs -I {} -P 10 bash -c "check_filter_local {} $VERSION" > "$TEMP_RESULTS"
else
    export -f check_filter
    # Check filters in parallel (up to 10 at a time)
    for filter in "${KNOWN_FILTERS[@]}"; do
        echo "$filter"
    done | xargs -I {} -P 10 bash -c "check_filter {} $VERSION" > "$TEMP_RESULTS"
fi

# Read results into array
AVAILABLE_FILTERS=()
while IFS= read -r filter; do
    if [ -n "$filter" ]; then
        AVAILABLE_FILTERS+=("$filter")
        echo "  ✓ $filter"
    fi
done < "$TEMP_RESULTS"

# Clean up
rm -f "$TEMP_RESULTS"

echo ""
echo "Found ${#AVAILABLE_FILTERS[@]} filters for Okapi $VERSION"

# Sort filters for consistent output
IFS=$'\n' SORTED_FILTERS=($(sort <<<"${AVAILABLE_FILTERS[*]}")); unset IFS

# Ensure output directory exists
mkdir -p "$OUTPUT_DIR"

# Generate pom.xml — inherits from parent, only declares filters + build paths
cat > "$OUTPUT_FILE" << EOF
<?xml version="1.0" encoding="UTF-8"?>
<!--
    Okapi Bridge - Build for Okapi $VERSION
    Generated: $(date -u +%Y-%m-%dT%H:%M:%SZ)
    Filters: ${#SORTED_FILTERS[@]}

    Inherits infrastructure deps (gRPC, Gson, etc.) and plugin config from parent.
    Only version-specific Okapi filter dependencies are declared here.
-->
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>neokapi</groupId>
        <artifactId>okapi-bridge-parent</artifactId>
        <version>$BRIDGE_VERSION</version>
        <relativePath>${PATH_PREFIX}pom.xml</relativePath>
    </parent>

    <artifactId>neokapi-bridge-okapi-$VERSION</artifactId>
    <packaging>jar</packaging>
    <name>Okapi Bridge - Okapi $VERSION</name>

    <properties>
        <okapi.version>$VERSION</okapi.version>
        <maven.compiler.source>$JAVA_VERSION</maven.compiler.source>
        <maven.compiler.target>$JAVA_VERSION</maven.compiler.target>
    </properties>

    <dependencies>
        <!-- Infrastructure deps (okapi-core, gRPC, Gson, etc.) inherited from parent.
             okapi-core version resolves via \${okapi.version} override above. -->

        <!-- Okapi Filters for $VERSION -->
EOF

for filter in "${SORTED_FILTERS[@]}"; do
    cat >> "$OUTPUT_FILE" << EOF
        <dependency>
            <groupId>net.sf.okapi.filters</groupId>
            <artifactId>$filter</artifactId>
            <version>\${okapi.version}</version>
        </dependency>
EOF
done

cat >> "$OUTPUT_FILE" << EOF
    </dependencies>

    <build>
        <!-- Compile bridge source from bridge-core module -->
        <sourceDirectory>${PATH_PREFIX}bridge-core/src/main/java</sourceDirectory>
        <extensions>
            <extension>
                <groupId>kr.motd.maven</groupId>
                <artifactId>os-maven-plugin</artifactId>
                <version>1.7.1</version>
            </extension>
        </extensions>
        <plugins>
            <!-- Protobuf + gRPC codegen (config inherited from parent) -->
            <plugin>
                <groupId>org.xolstice.maven.plugins</groupId>
                <artifactId>protobuf-maven-plugin</artifactId>
                <configuration>
                    <protoSourceRoot>${PATH_PREFIX}bridge-core/src/main/proto</protoSourceRoot>
                </configuration>
            </plugin>
            <!-- Add schema-generator source for exec:java -->
            <plugin>
                <groupId>org.codehaus.mojo</groupId>
                <artifactId>build-helper-maven-plugin</artifactId>
                <executions>
                    <execution>
                        <id>add-tools-source</id>
                        <phase>generate-sources</phase>
                        <goals>
                            <goal>add-source</goal>
                        </goals>
                        <configuration>
                            <sources>
                                <source>${PATH_PREFIX}tools/schema-generator/src/main/java</source>
                            </sources>
                        </configuration>
                    </execution>
                    <execution>
                        <id>add-tools-resources</id>
                        <phase>generate-resources</phase>
                        <goals>
                            <goal>add-resource</goal>
                        </goals>
                        <configuration>
                            <resources>
                                <resource>
                                    <directory>${PATH_PREFIX}schemagen</directory>
                                </resource>
                                <resource>
                                    <directory>\${project.build.directory}/schema-resources</directory>
                                </resource>
                            </resources>
                        </configuration>
                    </execution>
                </executions>
            </plugin>
            <!-- Exec plugin: schema generation (inherited from parent) + schema preparation -->
            <plugin>
                <groupId>org.codehaus.mojo</groupId>
                <artifactId>exec-maven-plugin</artifactId>
                <executions>
                    <execution>
                        <id>prepare-schemas</id>
                        <phase>generate-resources</phase>
                        <goals>
                            <goal>exec</goal>
                        </goals>
                        <configuration>
                            <executable>bash</executable>
                            <arguments>
                                <argument>${PATH_PREFIX}scripts/prepare-schemas-for-jar.sh</argument>
                                <argument>\${okapi.version}</argument>
                                <argument>\${project.build.directory}/schema-resources/schemas</argument>
                            </arguments>
                        </configuration>
                    </execution>
                </executions>
            </plugin>
            <!-- Assembly: fat JAR (config inherited from parent) -->
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-shade-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
EOF

echo "Generated $OUTPUT_FILE"
