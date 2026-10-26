#!/usr/bin/env bash
# Spot-check #157 stability on two Bhagyanager PDFs (not the full corpus).
# Run from repo root: bash scripts/doc-stability-spotcheck.sh
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO_ROOT"

LOG_DIR="$REPO_ROOT/target/stability-spotcheck"

# Default: clean slate before every real-doc spot-check (set PRESERVE_SPOTCHECK=1 to skip).
if [[ "${PRESERVE_SPOTCHECK:-0}" != "1" ]]; then
  bash "$REPO_ROOT/scripts/doc-stability-reset.sh"
else
  echo "PRESERVE_SPOTCHECK=1 — keeping existing DB/blobs/logs"
fi

mkdir -p "$LOG_DIR"
LOG_FILE="$LOG_DIR/run.log"
exec > >(tee "$LOG_FILE") 2>&1

echo "=== doc stability spotcheck $(date -u +"%Y-%m-%dT%H:%M:%SZ") ==="
echo "Log: $LOG_FILE"

load_dotenv() {
  local file="$1"
  local line key val
  while IFS= read -r line || [[ -n "$line" ]]; do
    line="${line//$'\r'/}"
    line="${line#"${line%%[![:space:]]*}"}"
    line="${line%"${line##*[![:space:]]}"}"
    [[ -z "$line" ]] && continue
    [[ "$line" == \#* ]] && continue
    [[ "$line" == //* ]] && continue
    if [[ "$line" == export\ * ]]; then
      line="${line#export }"
    fi
    if [[ "$line" != *=* ]]; then
      continue
    fi
    key="${line%%=*}"
    val="${line#*=}"
    key="${key%"${key##*[![:space:]]}"}"
    val="${val#"${val%%[![:space:]]*}"}"
    if [[ "$val" == \"*\" && "$val" == *\" ]]; then
      val="${val:1:${#val}-2}"
    elif [[ "$val" == \'*\' && "$val" == *\' ]]; then
      val="${val:1:${#val}-2}"
    fi
    export "$key=$val"
  done < "$file"
}

if [[ -f .env ]]; then
  load_dotenv .env
  echo "Loaded .env (KEY=value lines only; # and // comment lines skipped)"
else
  echo "WARNING: no .env in repo root — cloud extract may fail"
fi

if [[ -z "${LLAMA_CLOUD_API_KEY:-}" && -z "${llamaparse_api_key:-}" ]]; then
  echo "WARNING: LLAMA_CLOUD_API_KEY (or llamaparse_api_key) not set after sourcing .env"
fi

# Fast classpath launch (seconds). Avoid mvn package/shade (~2 min, silent, jar lock if two runs overlap).
CP_FILE="$REPO_ROOT/target/doc-stability.cp"
echo "Compiling tev-doc (mvn compile + classpath, not fat jar)..."
mvn -DskipTests compile dependency:build-classpath -Dmdep.outputFile="$CP_FILE"
TEV_CP="$REPO_ROOT/target/classes:$(cat "$CP_FILE")"

TEV_DOC=(
  java --enable-native-access=ALL-UNNAMED
  -cp "$TEV_CP"
  com.resurgent.tev.doc.TevDocMain
)
echo "tev-doc ready."

DB="$LOG_DIR/workspace.db"
MANDATE=1

run_one() {
  local LOGICAL="$1"
  local FILE="$2"
  local ROLE="${3:-other}"
  local PACK_OVERRIDE="${4:-}"

  if [[ ! -f "$FILE" ]]; then
    echo "ERROR: missing file: $FILE"
    exit 1
  fi

  echo ""
  echo "========== $LOGICAL =========="
  echo "File: $FILE"

  "${TEV_DOC[@]}" ingest \
    --mandate "$MANDATE" \
    --logical-doc "$LOGICAL" \
    --file "$FILE" \
    --file-role "$ROLE" \
    --db "$DB"

  VERSION="$(
    sqlite3 "$DB" "
      SELECT dv.document_version_id
      FROM document_version dv
      JOIN document d ON d.document_id = dv.document_id
      WHERE d.mandate_id = $MANDATE AND d.logical_name = '$LOGICAL'
      ORDER BY dv.document_version_id DESC LIMIT 1;
    "
  )"
  echo "document_version_id=$VERSION"

  echo "Layout (local PDF/OCR)…"
  "${TEV_DOC[@]}" layout --mandate "$MANDATE" --version "$VERSION" --db "$DB"

  echo "Parse (cloud triage / LlamaParse when needed)…"
  "${TEV_DOC[@]}" parse --version "$VERSION" --db "$DB"

  echo "Layout again (hydrate blocks from cloud parse text on scans)…"
  "${TEV_DOC[@]}" layout --mandate "$MANDATE" --version "$VERSION" --db "$DB"

  "${TEV_DOC[@]}" classify \
    --mandate "$MANDATE" \
    --version "$VERSION" \
    --industry general \
    --force \
    --db "$DB"

  echo "Classification:"
  sqlite3 -header -column "$DB" "
    SELECT doc_type_code, confidence
    FROM doc_classification
    WHERE document_version_id = $VERSION;
  "

  echo "Running two-pass stability (live LlamaExtract)..."
  STABILITY_ARGS=(
    stability
    --mandate "$MANDATE"
    --version "$VERSION"
    --db "$DB"
    --extractor llama-extract
  )
  if [[ -n "$PACK_OVERRIDE" ]]; then
    STABILITY_ARGS+=(--pack "$PACK_OVERRIDE")
  fi
  "${TEV_DOC[@]}" "${STABILITY_ARGS[@]}"

  echo "Unstable readings for this version:"
  sqlite3 -header -column "$DB" "
    SELECT field_key, line_key, value_a, value_b, page_a, page_b, reason
    FROM unstable_reading
    WHERE document_version_id = $VERSION;
  "
}

run_one "AOA spotcheck" \
  "Project Docs/Bhagyanager Solar Ltd. Client Docs/About the Company/Bhagyanagar Solar _ AOA.pdf" \
  identity

run_one "ATC audit spotcheck" \
  "Project Docs/Bhagyanager Solar Ltd. Client Docs/Audit_reports/ATC AUDIT REPORT 24-25.pdf" \
  technical_report \
  "${ATC_PACK:-financials.audited_summary}"

echo ""
echo "Done. DB: $DB"
