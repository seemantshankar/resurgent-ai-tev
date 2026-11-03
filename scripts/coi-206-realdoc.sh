#!/usr/bin/env bash
# Real COI checklist: facts + bind + optional pack leaf on Bhagyanagar COI.pdf.
# Layout already holds the MCA sentences; local reading does not need LlamaParse.
set -euo pipefail
REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO_ROOT"

RUN="${RUN_DIR:-target/realdoc-coi}"
COI="Project Docs/Bhagyanager Solar Ltd. Client Docs/About the Company/COI.pdf"
MANDATE=1

load_dotenv() {
  local file="$1" line key val
  [[ -f "$file" ]] || return 0
  while IFS= read -r line || [[ -n "$line" ]]; do
    line="${line//$'\r'/}"
    [[ -z "$line" || "$line" == \#* || "$line" == //* ]] && continue
    [[ "$line" == export\ * ]] && line="${line#export }"
    [[ "$line" != *=* ]] && continue
    key="${line%%=*}"; val="${line#*=}"
    key="${key%"${key##*[![:space:]]}"}"
    val="${val#"${val%%[![:space:]]*}"}"
    export "$key=$val"
  done < "$file"
}
load_dotenv .env

rm -rf "$RUN"
mkdir -p "$RUN/packs/general" "$RUN/blobs"
cat > "$RUN/packs/general/corporate_governance.certificate_of_incorporation@1.0.0.yaml" <<'YAML'
code: corporate_governance.certificate_of_incorporation
version: 1.0.0
family: certificate
file_role: certificate
industry_tags:
  - general
identity_signals: []
fields:
  - key: cin
    path: identity > CIN
    path_root: identity
    value_kind: text
    required: true
tables: []
checklist: []
report_sections: []
YAML
python3 -c "
import json, pathlib
p=pathlib.Path('$RUN/packs/registry.json')
p.write_text(json.dumps({'entries':[{'code':'corporate_governance.certificate_of_incorporation','version':'1.0.0','family':'certificate','fileRole':'certificate','industryTag':'general','fileHash':'thin','yamlPath':'general/corporate_governance.certificate_of_incorporation@1.0.0.yaml','sourceMandates':[],'inductionRunId':'manual','status':'published','createdAt':'2026-01-01T00:00:00Z','sourceDocuments':[]}]}, indent=2))
"

CP_FILE="$REPO_ROOT/target/doc-stability.cp"
mvn -q -DskipTests compile dependency:build-classpath -Dmdep.outputFile="$CP_FILE"
TEV_CP="$REPO_ROOT/target/classes:$(cat "$CP_FILE")"
TEV_DOC=(java --enable-native-access=ALL-UNNAMED -cp "$TEV_CP" com.resurgent.tev.doc.TevDocMain)

DB="$RUN/workspace.db"
LOG="$RUN/run.log"
exec > >(tee "$LOG") 2>&1

echo "=== COI checklist $(date -u +"%Y-%m-%dT%H:%M:%SZ") ==="
echo "RUN=$RUN"

"${TEV_DOC[@]}" ingest --mandate "$MANDATE" --logical-doc COI --file "$COI" \
  --file-role certificate --db "$DB" --blob-root "$RUN/blobs"
V=$(sqlite3 "$DB" "SELECT dv.document_version_id FROM document_version dv JOIN document d ON d.document_id=dv.document_id WHERE d.logical_name='COI' ORDER BY dv.document_version_id DESC LIMIT 1;")
D=$(sqlite3 "$DB" "SELECT document_id FROM document WHERE logical_name='COI' ORDER BY document_id DESC LIMIT 1;")
echo "document_id=$D document_version_id=$V"

"${TEV_DOC[@]}" layout --mandate "$MANDATE" --version "$V" --db "$DB" --blob-root "$RUN/blobs"
"${TEV_DOC[@]}" policy --mandate "$MANDATE" --db "$DB" --data-residency local_only

"${TEV_DOC[@]}" extract --mandate "$MANDATE" --version "$V" --db "$DB" --blob-root "$RUN/blobs" \
  --pack corporate_governance.certificate_of_incorporation --extractor doc-llm-extract

echo "--- facts ---"
sqlite3 -header -column "$DB" "
  SELECT f.field_key,
         substr(f.value_text,1,50) val,
         f.nomenclature_status,
         substr(ifnull(f.ontology_path,''),1,50) path,
         substr(ifnull(COALESCE(p.verbatim, e.source_text),''),1,80) quote
  FROM fact f
  LEFT JOIN fact_evidence e ON e.fact_id=f.fact_id
  LEFT JOIN provenance_doc p ON p.provenance_id=e.provenance_id
  WHERE f.document_version_id=$V ORDER BY f.field_key;"

echo "--- published packs ---"
ls -la "$RUN/packs/general/" || true
rg -n "version:|key:|required:" "$RUN/packs/general/"*.yaml || true

echo "--- second COI ---"
"${TEV_DOC[@]}" ingest --mandate "$MANDATE" --logical-doc "COI copy" --file "$COI" \
  --file-role certificate --db "$DB" --blob-root "$RUN/blobs"
V2=$(sqlite3 "$DB" "SELECT dv.document_version_id FROM document_version dv JOIN document d ON d.document_id=dv.document_id WHERE d.logical_name='COI copy' ORDER BY dv.document_version_id DESC LIMIT 1;")
"${TEV_DOC[@]}" layout --mandate "$MANDATE" --version "$V2" --db "$DB" --blob-root "$RUN/blobs"
"${TEV_DOC[@]}" extract --mandate "$MANDATE" --version "$V2" --db "$DB" --blob-root "$RUN/blobs" \
  --pack corporate_governance.certificate_of_incorporation --extractor doc-llm-extract
sqlite3 -header -column "$DB" "
  SELECT field_key, count(*) n
  FROM fact WHERE document_version_id=$V2
  GROUP BY field_key ORDER BY field_key;"

echo "Log: $LOG"
