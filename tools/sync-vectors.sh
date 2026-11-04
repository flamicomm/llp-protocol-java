#!/bin/sh
# Copy the LLP 4.0.0 conformance corpus from llp-spec into the test resources.
#
# The corpus is vendored (not read at test time from another checkout) so the
# repository is self-contained. MANIFEST.sha256 travels with it and
# VectorManifestTest verifies every file, so a partial copy fails the build.
#
# Usage:
#   tools/sync-vectors.sh [path-to-llp-spec]
#
# Default source: ../llp-spec (sibling checkout).

set -eu

SPEC_DIR="${1:-../llp-spec}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$ROOT/src/test/resources/llp-vectors"

if [ ! -d "$SPEC_DIR/vectors" ]; then
    echo "error: $SPEC_DIR/vectors not found" >&2
    echo "usage: $0 [path-to-llp-spec]" >&2
    exit 1
fi

mkdir -p "$DEST"
rm -f "$DEST"/*.json "$DEST"/MANIFEST.sha256
cp "$SPEC_DIR"/vectors/*.json "$SPEC_DIR"/vectors/MANIFEST.sha256 "$DEST"/

( cd "$DEST" && sha256sum -c MANIFEST.sha256 )

echo "synced $(ls "$DEST"/*.json | wc -l) files from $SPEC_DIR/vectors"
