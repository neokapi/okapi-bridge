//go:build parity

package parity

import (
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"sync"
)

// ModuleDir returns the absolute path of this module's root (the directory
// holding parity/go.mod), anchored on this source file so the answer does not
// depend on the working directory of the test binary.
func ModuleDir() (string, error) {
	_, thisFile, _, ok := runtime.Caller(0)
	if !ok {
		return "", errors.New("runtime.Caller failed")
	}
	return filepath.Dir(thisFile), nil
}

var (
	neokapiDirOnce sync.Once
	neokapiDir     string
	neokapiDirErr  error
)

// NeokapiDir returns the source directory of the github.com/neokapi/neokapi
// module this suite is compiled against. The suite reads the format specs
// (core/formats/<id>/spec.yaml) from there, so the specs and the readers under
// test always come from the same module version.
//
// KAPI_PARITY_NEOKAPI_DIR overrides the lookup; otherwise the directory is
// what `go list -m` reports for the module, run from this module's root.
func NeokapiDir() (string, error) {
	neokapiDirOnce.Do(func() {
		if dir := os.Getenv("KAPI_PARITY_NEOKAPI_DIR"); dir != "" {
			neokapiDir, neokapiDirErr = filepath.Abs(dir)
			return
		}
		mod, err := ModuleDir()
		if err != nil {
			neokapiDirErr = err
			return
		}
		cmd := exec.Command("go", "list", "-m", "-f", "{{.Dir}}", "github.com/neokapi/neokapi") //nolint:noctx // one-shot module lookup
		cmd.Dir = mod
		cmd.Stderr = os.Stderr
		out, err := cmd.Output()
		if err != nil {
			neokapiDirErr = fmt.Errorf("locate neokapi module: %w", err)
			return
		}
		dir := strings.TrimSpace(string(out))
		if dir == "" {
			neokapiDirErr = errors.New("go list -m reported no directory for github.com/neokapi/neokapi")
			return
		}
		neokapiDir = dir
	})
	return neokapiDir, neokapiDirErr
}

// NeokapiFormatsDir returns the core/formats directory of the neokapi module
// this suite is compiled against.
func NeokapiFormatsDir() (string, error) {
	dir, err := NeokapiDir()
	if err != nil {
		return "", err
	}
	return filepath.Join(dir, "core", "formats"), nil
}
