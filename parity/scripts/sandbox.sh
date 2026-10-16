#!/usr/bin/env bash
# Builds the parity sandbox: a released kapi binary plus a freshly built
# okapi-bridge plugin, isolated from the user's plugin directories.
#
# Prints the absolute sandbox path as the only line on stdout; status goes to
# stderr, so callers can capture it:
#
#   sandbox=$(parity/scripts/sandbox.sh)
#
# Layout:
#   <repo>/.parity/
#   ├── bin/kapi                      copied from $KAPI_BIN or the kapi on PATH
#   ├── plugins/okapi-bridge/         the v2 plugin tarball, unpacked
#   └── okapi-testdata/<version>/     upstream Okapi test resources (optional)
#
# Inputs (env):
#   KAPI_BIN         Path to the kapi binary to test. Default: `kapi` on PATH.
#   OKAPI_VERSION    Okapi Framework version to build the plugin for. Default 1.48.0.
#   PARITY_FORCE     When set, rebuild every part even if it exists.
#   PARITY_SKIP_TESTDATA  When set, do not fetch the okapi-testdata release.
#
# The script never consults $XDG_DATA_HOME or ~/.local/share/kapi: the suite
# measures the plugin that was just built, not whatever is installed.

set -euo pipefail

log() { echo "[parity-sandbox] $*" >&2; }

repo_root="$(cd "$(dirname "$0")/../.." && pwd)"
sandbox="${repo_root}/.parity"
plugins_dir="${sandbox}/plugins"
bin_dir="${sandbox}/bin"
bridge_install_dir="${plugins_dir}/okapi-bridge"
okapi_version="${OKAPI_VERSION:-1.48.0}"

mkdir -p "$bin_dir" "$plugins_dir"

# 1. The kapi binary under test: a released build, copied into the sandbox.
if [ -n "${PARITY_FORCE:-}" ] || [ ! -x "${bin_dir}/kapi" ]; then
  kapi_bin="${KAPI_BIN:-$(command -v kapi || true)}"
  if [ -z "$kapi_bin" ] || [ ! -x "$kapi_bin" ]; then
    echo "[parity-sandbox] FATAL: no kapi binary: set KAPI_BIN or put kapi on PATH" >&2
    exit 1
  fi
  log "kapi: $kapi_bin ($("$kapi_bin" version 2>/dev/null | head -1))"
  /bin/cp -f "$kapi_bin" "${bin_dir}/kapi"
  chmod +x "${bin_dir}/kapi"
else
  log "kapi already in sandbox at ${bin_dir}/kapi (set PARITY_FORCE=1 to replace)"
fi

# 2. The okapi-bridge v2 plugin tarball for the host platform.
host_os="$(uname -s | tr '[:upper:]' '[:lower:]')"
host_arch="$(uname -m | sed 's/x86_64/amd64/;s/aarch64/arm64/')"
tarball_name="kapi-okapi-bridge_${okapi_version}_${host_os}_${host_arch}.tar.gz"
tarball_path="${repo_root}/dist/v2/${tarball_name}"

if [ -n "${PARITY_FORCE:-}" ] || [ ! -f "$tarball_path" ]; then
  log "building okapi-bridge plugin v2 (V=${okapi_version})"
  (cd "$repo_root" && make plugin-v2 V="$okapi_version") >&2
else
  log "okapi-bridge tarball cached at ${tarball_path} (set PARITY_FORCE=1 to rebuild)"
fi

if [ ! -f "$tarball_path" ]; then
  echo "[parity-sandbox] FATAL: tarball not produced at $tarball_path" >&2
  exit 1
fi

# 3. Unpack into the sandbox plugins dir.
if [ -n "${PARITY_FORCE:-}" ] || [ ! -d "$bridge_install_dir" ]; then
  log "unpacking ${tarball_path} → ${bridge_install_dir}"
  rm -rf "$bridge_install_dir"
  mkdir -p "$bridge_install_dir"
  tar -xzf "$tarball_path" -C "$plugins_dir"
else
  log "okapi-bridge already installed at ${bridge_install_dir}"
fi

if [ ! -f "${bridge_install_dir}/manifest.json" ]; then
  echo "[parity-sandbox] FATAL: ${bridge_install_dir}/manifest.json missing after unpack" >&2
  exit 1
fi

# 4. The upstream Okapi test resources this repository publishes as the
# `okapi-testdata-<version>` release. Round-trip tests read binary fixtures
# (.idml, .docx, …) from this tree.
testdata_dir="${sandbox}/okapi-testdata/${okapi_version}"
testdata_archive="${sandbox}/okapi-testdata/okapi-testdata-${okapi_version}.tar.gz"
testdata_url="https://github.com/neokapi/okapi-bridge/releases/download/okapi-testdata-${okapi_version}/okapi-testdata.tar.gz"
if [ -n "${PARITY_SKIP_TESTDATA:-}" ]; then
  log "skipping okapi-testdata fetch (PARITY_SKIP_TESTDATA set)"
elif [ -n "${PARITY_FORCE:-}" ] || [ ! -d "$testdata_dir" ]; then
  log "fetching okapi test resources (v${okapi_version}) from $testdata_url"
  mkdir -p "$(dirname "$testdata_archive")" "$testdata_dir"
  if ! curl -fL --retry 3 -o "$testdata_archive" "$testdata_url" 2>&1 | sed 's/^/[parity-sandbox] /' >&2; then
    log "WARNING: could not fetch okapi-testdata-${okapi_version}; tests that need it will fail"
  else
    rm -rf "$testdata_dir"
    mkdir -p "$testdata_dir"
    tar -xzf "$testdata_archive" -C "$testdata_dir"
    log "extracted okapi test resources → $testdata_dir"
  fi
else
  log "okapi test resources already extracted at $testdata_dir"
fi

echo "$sandbox"
