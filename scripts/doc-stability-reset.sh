#!/usr/bin/env bash
# Wipe Bhagyanager / #157 spot-check artifacts so prior runs cannot pollute results.
# Usage (from repo root):
#   bash scripts/doc-stability-reset.sh
#   SPOTCHECK_DIR=/path/to/dir bash scripts/doc-stability-reset.sh
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SPOTCHECK_DIR="${SPOTCHECK_DIR:-$REPO_ROOT/target/stability-spotcheck}"

echo "Resetting spot-check workspace: $SPOTCHECK_DIR"

rm -rf "$SPOTCHECK_DIR/blobs"
rm -rf "$SPOTCHECK_DIR/packs"
rm -f "$SPOTCHECK_DIR/workspace.db" \
  "$SPOTCHECK_DIR/workspace.db-wal" \
  "$SPOTCHECK_DIR/workspace.db-shm" \
  "$SPOTCHECK_DIR/run.log" \
  "$SPOTCHECK_DIR/atc-continue.log"

mkdir -p "$SPOTCHECK_DIR"
echo "Removed: SQLite workspace, blobs/, packs/, run logs."
