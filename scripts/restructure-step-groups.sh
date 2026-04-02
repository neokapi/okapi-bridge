#!/bin/bash
# Restructure step composite schemas that have a "groups" array
# Moves flat properties into nested group objects and generates x-groups metadata.
#
# Usage: restructure-step-groups.sh <composite.json> <output.json>
#
# If the schema has no "groups" array, copies as-is (no-op).
# Properties without a "group" field remain at root level.

set -euo pipefail

if [[ $# -lt 2 ]]; then
    echo "Usage: $0 <composite.json> <output.json>" >&2
    exit 1
fi

INPUT="$1"
OUTPUT="$2"

if [[ ! -f "$INPUT" ]]; then
    echo "Error: Input file not found: $INPUT" >&2
    exit 1
fi

# Check if schema has groups — if not, copy as-is
has_groups=$(jq 'has("groups")' "$INPUT")
if [[ "$has_groups" != "true" ]]; then
    cp "$INPUT" "$OUTPUT"
    exit 0
fi

jq '
  # Save the groups array for x-groups generation
  .groups as $groups |

  # Partition properties: grouped vs ungrouped
  (.properties | to_entries | group_by(.value.group // "")) as $partitions |

  # Build nested group objects
  ($groups | map(.id)) as $group_ids |

  # Create the nested properties object
  (reduce $partitions[] as $part ({};
    ($part[0].value.group // null) as $gid |
    if $gid == null or ($group_ids | index($gid) | not) then
      # Ungrouped properties stay at root
      . + ($part | map({key: .key, value: (.value | del(.group))}) | from_entries)
    else
      # Grouped properties go into a nested object
      ($groups | map(select(.id == $gid)) | first) as $gdef |
      .[$gid] = {
        type: "object",
        title: $gdef.label,
        properties: ($part | map({key: .key, value: (.value | del(.group))}) | from_entries)
      }
    end
  )) as $new_props |

  # Generate x-groups array (ordered by the groups definition)
  ($groups | map({
    id: .id,
    label: .label,
    fields: [.id]
  })) as $x_groups |

  # Rebuild the schema
  del(.groups) |
  .properties = $new_props |
  .["x-groups"] = $x_groups
' "$INPUT" > "$OUTPUT"
