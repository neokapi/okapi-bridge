//go:build parity

package formats

import (
	"path/filepath"
	"testing"

	"github.com/neokapi/okapi-bridge/parity"
)

// nativeSpecPath returns the path of core/formats/<id>/spec.yaml inside the
// neokapi module this suite is compiled against, so the spec a test drives
// describes the reader it links.
func nativeSpecPath(t *testing.T, id string) string {
	t.Helper()
	dir, err := parity.NeokapiFormatsDir()
	if err != nil {
		t.Fatalf("locate neokapi formats dir: %v", err)
	}
	return filepath.Join(dir, id, "spec.yaml")
}
