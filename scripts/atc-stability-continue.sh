#!/usr/bin/env bash
# Continue ATC stability on version 3 in the spotcheck DB (after failed unknown classify).
# From repo root: bash scripts/atc-stability-continue.sh
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO_ROOT"

LOG_DIR="$REPO_ROOT/target/stability-spotcheck"

# Full clean + re-ingest ATC only: RESET_ATC=1 (wipes DB/blobs; version id becomes 1).
# Continue on existing spotcheck DB: default RESET_ATC=0.
if [[ "${RESET_ATC:-0}" == "1" ]]; then
  bash "$REPO_ROOT/scripts/doc-stability-reset.sh"
fi

mkdir -p "$LOG_DIR"
LOG_FILE="$LOG_DIR/atc-continue.log"
exec > >(tee "$LOG_FILE") 2>&1
echo "=== ATC continue $(date -u +"%Y-%m-%dT%H:%M:%SZ") ==="
echo "Log: $LOG_FILE (stderr progress from tev-doc is merged here)"

load_dotenv() {
  local file="$1" line key val
  while IFS= read -r line || [[ -n "$line" ]]; do
    line="${line//$'\r'/}"
    line="${line#"${line%%[![:space:]]*}"}"
    line="${line%"${line##*[![:space:]]}"}"
    [[ -z "$line" ]] && continue
    [[ "$line" == \#* ]] && continue
    [[ "$line" == //* ]] && continue
    [[ "$line" == export\ * ]] && line="${line#export }"
    [[ "$line" != *=* ]] && continue
    key="${line%%=*}"; val="${line#*=}"
    key="${key%"${key##*[![:space:]]}"}"
    val="${val#"${val%%[![:space:]]*}"}"
    export "$key=$val"
  done < "$file"
}

[[ -f .env ]] && load_dotenv .env

DB="$REPO_ROOT/target/stability-spotcheck/workspace.db"
CP_FILE="$REPO_ROOT/target/doc-stability.cp"
VERSION="${ATC_VERSION:-${VERSION:-3}}"
MANDATE="${MANDATE:-1}"
ATC_FILE="${ATC_FILE:-Project Docs/Bhagyanager Solar Ltd. Client Docs/Audit_reports/ATC AUDIT REPORT 24-25.pdf}"
# Override if your benchmark uses an induced deep audit pack:
PACK="${ATC_PACK:-financials.audited_summary}"

echo "Compiling tev-doc (mvn compile + classpath)…"
mvn -DskipTests compile dependency:build-classpath -Dmdep.outputFile="$CP_FILE"
DOC=(java --enable-native-access=ALL-UNNAMED -cp "$REPO_ROOT/target/classes:$(cat "$CP_FILE")" com.resurgent.tev.doc.TevDocMain)

step() { echo ""; echo ">>> $(date -u +"%H:%M:%SZ") $*"; }

if [[ "${RESET_ATC:-0}" == "1" ]]; then
  step "ingest (fresh ATC after reset)"
  if [[ ! -f "$ATC_FILE" ]]; then
    echo "ERROR: missing file: $ATC_FILE"
    exit 1
  fi
  "${DOC[@]}" ingest \
    --mandate "$MANDATE" \
    --logical-doc "ATC audit spotcheck" \
    --file "$ATC_FILE" \
    --file-role technical_report \
    --db "$DB"
  VERSION="$(
    sqlite3 "$DB" "
      SELECT dv.document_version_id
      FROM document_version dv
      JOIN document d ON d.document_id = dv.document_id
      WHERE d.mandate_id = $MANDATE AND d.logical_name = 'ATC audit spotcheck'
      ORDER BY dv.document_version_id DESC LIMIT 1;
    "
  )"
  echo "document_version_id=$VERSION (after reset ingest)"
fi

if [[ ! -f "$DB" ]]; then
  echo "ERROR: no workspace at $DB — run bash scripts/doc-stability-spotcheck.sh first,"
  echo "       or RESET_ATC=1 bash scripts/atc-stability-continue.sh for ATC-only clean run."
  exit 1
fi

step "parse (version $VERSION)"
"${DOC[@]}" parse --version "$VERSION" --db "$DB"
step "layout (mandate $MANDATE, version $VERSION)"
"${DOC[@]}" layout --mandate "$MANDATE" --version "$VERSION" --db "$DB"
step "classify (version $VERSION)"
"${DOC[@]}" classify --mandate "$MANDATE" --version "$VERSION" --industry general --force --db "$DB"

step "stability (pack=$PACK)"
"${DOC[@]}" stability --mandate "$MANDATE" --version "$VERSION" --db "$DB" --pack "$PACK" --extractor llama-extract

echo "=== unstable_reading rows ==="
sqlite3 -header -column "$DB" "
  SELECT field_key, line_key, value_a, value_b, page_a, page_b, reason
  FROM unstable_reading WHERE document_version_id = $VERSION;
"
