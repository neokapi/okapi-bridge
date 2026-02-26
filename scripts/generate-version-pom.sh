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
# Usage: ./scripts/generate-version-pom.sh <version>

set -e

VERSION=$1
if [ -z "$VERSION" ]; then
    echo "Usage: $0 <okapi-version>"
    exit 1
fi

OUTPUT_DIR="okapi-releases/$VERSION"
OUTPUT_FILE="$OUTPUT_DIR/pom.xml"
META_FILE="$OUTPUT_DIR/meta.json"

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

# Function to check a single filter
check_filter() {
    local filter=$1
    local version=$2
    # Check Okapi's repo first (more reliable), then Maven Central
    local url1="https://okapiframework.org/maven2/net/sf/okapi/filters/$filter/$version/$filter-$version.pom"
    local url2="https://repo1.maven.org/maven2/net/sf/okapi/filters/$filter/$version/$filter-$version.pom"
    if curl --output /dev/null --silent --head --fail --max-time 5 "$url1" 2>/dev/null || \
       curl --output /dev/null --silent --head --fail --max-time 5 "$url2" 2>/dev/null; then
        echo "$filter"
    fi
}

export -f check_filter

# Check filters in parallel (up to 10 at a time)
for filter in "${KNOWN_FILTERS[@]}"; do
    echo "$filter"
done | xargs -I {} -P 10 bash -c "check_filter {} $VERSION" > "$TEMP_RESULTS"

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
        <groupId>com.gokapi</groupId>
        <artifactId>okapi-bridge-parent</artifactId>
        <version>$BRIDGE_VERSION</version>
        <relativePath>../../pom.xml</relativePath>
    </parent>

    <artifactId>gokapi-bridge-okapi-$VERSION</artifactId>
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

cat >> "$OUTPUT_FILE" << 'EOF'
    </dependencies>

    <build>
        <!-- Compile bridge source from bridge-core module -->
        <sourceDirectory>../../bridge-core/src/main/java</sourceDirectory>
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
                    <protoSourceRoot>../../bridge-core/src/main/proto</protoSourceRoot>
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
                                <source>../../tools/schema-generator/src/main/java</source>
                            </sources>
                        </configuration>
                    </execution>
                </executions>
            </plugin>
            <!-- Shade: fat JAR (config inherited from parent) -->
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-shade-plugin</artifactId>
            </plugin>
            <!-- Exec: schema generation (config inherited from parent) -->
            <plugin>
                <groupId>org.codehaus.mojo</groupId>
                <artifactId>exec-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
EOF

echo "Generated $OUTPUT_FILE"
