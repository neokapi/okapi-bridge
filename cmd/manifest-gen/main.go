// Command manifest-gen produces a v2 manifest.json for the okapi-bridge
// plugin (issue neokapi/neokapi#438). It reads the JSON output of the
// bridge's `--list-filters` flag and emits a manifest declaring every Okapi
// filter as a Mode-C format capability.
//
// Usage:
//
//	manifest-gen \
//	  --okapi-version 1.47.0 \
//	  --filters-json ./filters.json \
//	  --plugin okapi-bridge \
//	  --binary kapi-okapi-bridge \
//	  --output ./manifest.json
//
// The schema of --filters-json is the JSON Gson dumps from
// neokapi.bridge.model.FilterInfo via OkapiBridgeServer.listFiltersAndExit.
package main

import (
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"os"
	"sort"
	"strings"
)

// FilterInfo mirrors neokapi.bridge.model.FilterInfo.
type FilterInfo struct {
	FilterClass string   `json:"filter_class"`
	ID          string   `json:"id"`
	Name        string   `json:"name"`
	DisplayName string   `json:"display_name"`
	MimeTypes   []string `json:"mime_types"`
	Extensions  []string `json:"extensions"`
	// Capabilities lists supported operations: "read", "write".
	// FilterRegistry sets these per-filter; missing means "read","write" both
	// (the historical default).
	Capabilities []string `json:"capabilities"`
}

// ListFiltersOutput is the top-level JSON shape from --list-filters.
type ListFiltersOutput struct {
	Filters []FilterInfo `json:"filters"`
}

// Manifest is the v2 manifest.json. We define a local Manifest type rather
// than depending on neokapi to keep this generator self-contained.
//
// See neokapi/core/plugin/manifest/manifest.go for the canonical Go types.
type Manifest struct {
	ManifestVersion string         `json:"manifest_version"`
	Plugin          string         `json:"plugin"`
	Version         string         `json:"version"`
	Description     string         `json:"description,omitempty"`
	Homepage        string         `json:"homepage,omitempty"`
	Author          string         `json:"author,omitempty"`
	License         string         `json:"license,omitempty"`
	Binary          string         `json:"binary"`
	MinKapiVersion  string         `json:"min_kapi_version,omitempty"`
	Group           string         `json:"group,omitempty"`
	Capabilities    Capabilities   `json:"capabilities"`
	Daemon          *DaemonConfig  `json:"daemon,omitempty"`
}

// Capabilities groups capability sections. We only populate Formats here.
type Capabilities struct {
	Formats []Format `json:"formats,omitempty"`
}

// Format declares one format reader/writer.
type Format struct {
	Name         string   `json:"name"`
	DisplayName  string   `json:"display_name,omitempty"`
	Description  string   `json:"description,omitempty"`
	Extensions   []string `json:"extensions,omitempty"`
	MimeTypes    []string `json:"mime_types,omitempty"`
	Capabilities []string `json:"capabilities,omitempty"`
	Schema       string   `json:"schema,omitempty"`
}

// DaemonConfig declares Mode-C daemon behavior.
type DaemonConfig struct {
	IdleTimeoutSeconds    int       `json:"idle_timeout_seconds,omitempty"`
	StartupTimeoutSeconds int       `json:"startup_timeout_seconds,omitempty"`
	Handshake             *Handshake `json:"handshake,omitempty"`
}

// Handshake describes the daemon handshake protocol.
type Handshake struct {
	Type   string   `json:"type"`
	Fields []string `json:"fields,omitempty"`
}

// skipFilters mirrors core/plugin/loader/loader.go: AutoXLIFFFilter is
// a delegating meta-filter whose lifecycle confuses the bridge. Skip it.
var skipFilters = map[string]bool{
	"okf_autoxliff": true,
}

// Options drives Generate. Exposed as a struct so tests don't have to round-
// trip via flags.
type Options struct {
	Plugin                string
	Version               string
	Binary                string
	License               string
	Description           string
	Homepage              string
	Author                string
	Group                 string
	MinKapiVersion        string
	IdleTimeoutSeconds    int
	StartupTimeoutSeconds int
	SchemaPathPattern     string // e.g. "schemas/filters/composite/{id}.schema.json"; "" → empty
	IncludeAutoXLIFF      bool   // for tests; production false
}

func main() {
	var (
		okapiVersion         = flag.String("okapi-version", "", "Okapi Framework version that this bridge bundles (required)")
		filtersJSON          = flag.String("filters-json", "", "Path to OkapiBridgeServer --list-filters JSON output (required)")
		output               = flag.String("output", "manifest.json", "Output path for manifest.json")
		plugin               = flag.String("plugin", "okapi-bridge", "Plugin name (also used as install dir name)")
		binary               = flag.String("binary", "kapi-okapi-bridge", "Plugin binary name (must match the shim binary inside the tarball)")
		license              = flag.String("license", "Apache-2.0", "Plugin SPDX license id")
		description          = flag.String("description", "Okapi Framework filters via gRPC bridge", "One-line description")
		homepage             = flag.String("homepage", "https://github.com/neokapi/okapi-bridge", "Homepage URL")
		author               = flag.String("author", "neokapi", "Plugin author")
		group                = flag.String("group", "neokapi", "Logical plugin grouping")
		minKapiVersion       = flag.String("min-kapi-version", "", "Minimum kapi version constraint, e.g. >=2.0.0")
		idleTimeout          = flag.Int("idle-timeout", 300, "Daemon idle timeout in seconds")
		startupTimeout       = flag.Int("startup-timeout", 30, "Daemon startup timeout in seconds")
		schemaPathPattern    = flag.String("schema-path", "schemas/filters/composite/{id}.schema.json", "Path pattern (with {id} placeholder) for per-format schema files; pass an empty string to omit")
	)
	flag.Parse()

	if *okapiVersion == "" {
		dieUsage("--okapi-version is required")
	}
	if *filtersJSON == "" {
		dieUsage("--filters-json is required")
	}

	raw, err := os.ReadFile(*filtersJSON)
	if err != nil {
		die(fmt.Errorf("read --filters-json: %w", err))
	}

	opts := Options{
		Plugin:                *plugin,
		Version:               *okapiVersion,
		Binary:                *binary,
		License:               *license,
		Description:           *description,
		Homepage:              *homepage,
		Author:                *author,
		Group:                 *group,
		MinKapiVersion:        *minKapiVersion,
		IdleTimeoutSeconds:    *idleTimeout,
		StartupTimeoutSeconds: *startupTimeout,
		SchemaPathPattern:     *schemaPathPattern,
	}

	manifest, err := Generate(raw, opts)
	if err != nil {
		die(err)
	}

	if err := writeJSON(*output, manifest); err != nil {
		die(fmt.Errorf("write manifest: %w", err))
	}
}

// Generate parses the --list-filters JSON and produces a manifest using opts.
func Generate(filtersJSON []byte, opts Options) (*Manifest, error) {
	if opts.Plugin == "" {
		return nil, errors.New("plugin name required")
	}
	if opts.Version == "" {
		return nil, errors.New("plugin version required")
	}
	if opts.Binary == "" {
		return nil, errors.New("plugin binary required")
	}

	var listed ListFiltersOutput
	if err := json.Unmarshal(filtersJSON, &listed); err != nil {
		return nil, fmt.Errorf("parse filters JSON: %w", err)
	}

	// Dedup by ID before emitting Format entries. The bridge's filter
	// discovery surfaces every class implementing IFilter, including
	// rainbowkit's stub variants (e.g. net.sf.okapi.filters.rainbowkit.
	// XLIFF2Filter shadows okapi-filter-xliff2's XLIFF2Filter — both
	// derive id "okf_xliff2"). Without dedup we'd emit two `okf_xliff2`
	// format entries in the manifest, which kapi's plugin host treats
	// as a same-plugin conflict and refuses to dispatch — printing
	// `format "okf_xliff2" is provided by plugins "okapi-bridge" and
	// "okapi-bridge"`. Mirror FilterRegistry.resolveFilterClass: when
	// multiple filters share an ID, keep the non-rainbowkit one.
	deduped := dedupFiltersByID(listed.Filters)

	formats := make([]Format, 0, len(deduped))
	for _, f := range deduped {
		if !opts.IncludeAutoXLIFF && skipFilters[f.ID] {
			continue
		}
		if f.ID == "" {
			continue
		}
		formats = append(formats, Format{
			Name:         f.ID,
			DisplayName:  pickDisplayName(f),
			Extensions:   normalizeExtensions(f.Extensions),
			MimeTypes:    dedupeStrings(f.MimeTypes),
			Capabilities: pickCapabilities(f.Capabilities),
			Schema:       expandSchemaPath(opts.SchemaPathPattern, f.ID),
		})
	}
	// Stable order so identical inputs produce identical output (helps CI
	// avoid spurious diffs).
	sort.SliceStable(formats, func(i, j int) bool { return formats[i].Name < formats[j].Name })

	return &Manifest{
		ManifestVersion: "1",
		Plugin:          opts.Plugin,
		Version:         opts.Version,
		Description:     opts.Description,
		Homepage:        opts.Homepage,
		Author:          opts.Author,
		License:         opts.License,
		Binary:          opts.Binary,
		Group:           opts.Group,
		MinKapiVersion:  opts.MinKapiVersion,
		Capabilities:    Capabilities{Formats: formats},
		Daemon: &DaemonConfig{
			IdleTimeoutSeconds:    opts.IdleTimeoutSeconds,
			StartupTimeoutSeconds: opts.StartupTimeoutSeconds,
			Handshake: &Handshake{
				Type:   "stdio-handshake",
				Fields: []string{"socket", "version"},
			},
		},
	}, nil
}

// pickDisplayName returns the display_name when present, else the name, else
// the id.
func pickDisplayName(f FilterInfo) string {
	if f.DisplayName != "" {
		return f.DisplayName
	}
	if f.Name != "" {
		return f.Name
	}
	return f.ID
}

// pickCapabilities returns the filter's declared capabilities, or the
// historical default ["read","write"] when missing.
func pickCapabilities(caps []string) []string {
	if len(caps) == 0 {
		return []string{"read", "write"}
	}
	return dedupeStrings(caps)
}

// normalizeExtensions trims whitespace and normalizes leading dots so each
// returned extension starts with exactly one ".". Empty strings are dropped.
func normalizeExtensions(exts []string) []string {
	if len(exts) == 0 {
		return nil
	}
	seen := make(map[string]struct{}, len(exts))
	out := make([]string, 0, len(exts))
	for _, raw := range exts {
		ext := strings.TrimSpace(raw)
		if ext == "" {
			continue
		}
		if !strings.HasPrefix(ext, ".") {
			ext = "." + ext
		}
		if _, ok := seen[ext]; ok {
			continue
		}
		seen[ext] = struct{}{}
		out = append(out, ext)
	}
	return out
}

// dedupeStrings removes empty entries and duplicates while preserving order.
func dedupeStrings(in []string) []string {
	if len(in) == 0 {
		return nil
	}
	seen := make(map[string]struct{}, len(in))
	out := make([]string, 0, len(in))
	for _, s := range in {
		s = strings.TrimSpace(s)
		if s == "" {
			continue
		}
		if _, ok := seen[s]; ok {
			continue
		}
		seen[s] = struct{}{}
		out = append(out, s)
	}
	return out
}

// expandSchemaPath substitutes {id} in pattern with id. Empty pattern returns
// "" so the manifest field is omitted.
func expandSchemaPath(pattern, id string) string {
	if pattern == "" {
		return ""
	}
	return strings.ReplaceAll(pattern, "{id}", id)
}

// dedupFiltersByID collapses entries with the same ID into one,
// preferring filter classes outside the `net.sf.okapi.filters.rainbowkit`
// package. Rainbowkit ships stub variants of common filters whose
// createFilterWriter() returns null, which breaks the pseudo pipeline;
// FilterRegistry.resolveFilterClass already prefers the canonical
// implementation at runtime, so the manifest must agree.
//
// Input order is otherwise preserved (first non-rainbowkit per ID
// wins; if there is no non-rainbowkit alternative, the rainbowkit
// entry is kept as a fallback).
func dedupFiltersByID(in []FilterInfo) []FilterInfo {
	type slot struct {
		idx     int // position in `out`
		isStub  bool
	}
	chosen := make(map[string]slot, len(in))
	out := make([]FilterInfo, 0, len(in))
	for _, f := range in {
		if f.ID == "" {
			out = append(out, f)
			continue
		}
		isStub := strings.Contains(f.FilterClass, ".rainbowkit.")
		if cur, ok := chosen[f.ID]; ok {
			if cur.isStub && !isStub {
				out[cur.idx] = f
				cur.isStub = false
				chosen[f.ID] = cur
			}
			// Otherwise keep the existing entry — it's either canonical
			// (and we ignore the stub) or both are stubs.
			continue
		}
		chosen[f.ID] = slot{idx: len(out), isStub: isStub}
		out = append(out, f)
	}
	return out
}

func writeJSON(path string, m *Manifest) error {
	f, err := os.Create(path)
	if err != nil {
		return err
	}
	defer f.Close()
	enc := json.NewEncoder(f)
	enc.SetIndent("", "  ")
	if err := enc.Encode(m); err != nil {
		return err
	}
	return nil
}

func dieUsage(msg string) {
	fmt.Fprintln(os.Stderr, "manifest-gen:", msg)
	flag.CommandLine.SetOutput(os.Stderr)
	flag.Usage()
	os.Exit(2)
}

func die(err error) {
	fmt.Fprintln(os.Stderr, "manifest-gen:", err)
	os.Exit(1)
}
