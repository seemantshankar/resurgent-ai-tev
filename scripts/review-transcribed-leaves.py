#!/usr/bin/env python3
"""Tier 2 review pack for transcribed soft leaves.

A soft leaf is 'transcribed' when the leaf segment is raw workbook text — a tax
rate, a quantity, a spec code, a supplier name, or a broken reference — rather
than a category. Policy: that text stays on the row as evidence and the row is
filed under a real category. Promoting it into the catalogue would freeze the
transcription.

For each flagged leaf this emits the grounding evidence (sheet, coordinate,
verbatim) plus a recommended action:

  MAP_EXISTING      a frozen catalogue leaf already covers it
  MAP_VIA_ALIAS     an alias for exactly this text already existed and was unused
  ADD_CATEGORY      a real category is needed and does not exist yet
  DROP_RATE_EVIDENCE a rate/percentage cell: keep the number as evidence, not a leaf
  DROP_DATA_ERROR   a broken reference became a taxonomy path

Usage:
  python3 scripts/review-transcribed-leaves.py --db path/workspace.db --out-dir out/
"""
from __future__ import annotations

import argparse
import csv
import re
import sqlite3
import sys
from pathlib import Path

FLAGS = [
    ("broken_ref", re.compile(r"#ref!", re.I)),
    ("supplier", re.compile(r"\bsupplier\b|pvt\.?\s*ltd|\bltd\.?\b", re.I)),
    ("tax_rate", re.compile(r"\b(cgst|sgst|igst|gst|vat|tds|cess)\b|@\s*\d+(\.\d+)?\s*%", re.I)),
    ("quantity_or_rate", re.compile(r"\d[\d,]*\s*(kva|kw|hp|sq\.?\s*ft|sqft|mm|mtr|meter|ft|ltr|kg|ton|rs\.?/|rs\b)|@\s*\d", re.I)),
    ("spec_code", re.compile(r'"|ø|\bpf\b|\bhz\b|\d{3,}\s*v\b', re.I)),
    ("negation_or_qualifier", re.compile(r"^\s*(less|add)\s*[:\-]|as per (estimate|quotation)|taken as|including gst", re.I)),
]

CATALOGUE_TARGETS = [
    (re.compile(r"elevator|lift", re.I), "Project Cost > Plant & Machinery > Elevator / Lift"),
    (re.compile(r"kitchen", re.I), "Project Cost > Plant & Machinery > Kitchen Equipments"),
    (re.compile(r"air\s*condition|^ac$|\bac\b|aircond", re.I), "Project Cost > Plant & Machinery > Air Conditioning"),
    (re.compile(r"waste\s*water|wastewater|etp|sewage", re.I), "Project Cost > Plant & Machinery > Wastewater / ETP"),
    (re.compile(r"civil work|plumbing|sanitary|rain water|interior", re.I), "Project Cost > Civil Works"),
    (re.compile(r"furniture|accessor", re.I), "Project Cost > Misc. Fixed Assets / Furniture & Fixtures"),
    (re.compile(r"genset|kva|volvo|penta", re.I), "Project Cost > Plant & Machinery"),
    (re.compile(r"electrif|\bled\b|ud5310|sky vision", re.I), "Project Cost > Electrical Installations"),
]

NEW_CATEGORY_TARGETS = [
    (re.compile(r"closing stock", re.I), "Profit & Loss > Closing Stock"),
    (re.compile(r"opening stock", re.I), "Profit & Loss > Opening Stock"),
]

DROP_RATE = re.compile(r"^(cgst|sgst|igst|gst|vat|tds)\b|@\s*\d+(\.\d+)?\s*%|%\s*of civil", re.I)


def normalise(text: str) -> str:
    return re.sub(r"[^a-z0-9]", "", (text or "").lower())


def classify(leaf: str) -> tuple[str, bool]:
    for name, pattern in FLAGS:
        if pattern.search(leaf):
            return name, True
    if len(leaf) > 60:
        return "overlong", True
    return "category_like", False


def recommend(leaf: str, parent: str, catalogue: set[str], alias_leaf: str) -> tuple[str, str]:
    if re.search(r"#ref!", leaf, re.I):
        return "", "DROP_DATA_ERROR"
    for pattern, target in CATALOGUE_TARGETS:
        if pattern.search(leaf):
            return target, "MAP_EXISTING" if target in catalogue else "ADD_CATEGORY"
    for pattern, target in NEW_CATEGORY_TARGETS:
        if pattern.search(leaf):
            return target, "ADD_CATEGORY"
    if DROP_RATE.search(leaf):
        return "", "DROP_RATE_EVIDENCE"
    if alias_leaf:
        return alias_leaf, "MAP_VIA_ALIAS"
    if parent in catalogue:
        return parent, "MAP_TO_PARENT"
    return "", "ADD_CATEGORY"


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--db", required=True, type=Path)
    ap.add_argument("--out-dir", required=True, type=Path)
    ap.add_argument("--run-label", default="")
    ap.add_argument("--other-db", type=Path, help="second run, to mark overlap")
    args = ap.parse_args()

    conn = sqlite3.connect(f"file:{args.db}?mode=ro", uri=True)
    conn.row_factory = sqlite3.Row
    catalogue = {r["path"] for r in conn.execute("select path from nomenclature_node")}
    aliases = {normalise(r["alias_text"]): r["leaf_path"]
               for r in conn.execute("select alias_text, leaf_path from nomenclature_alias")}

    overlap: set[str] = set()
    if args.other_db and args.other_db.is_file():
        other = sqlite3.connect(f"file:{args.other_db}?mode=ro", uri=True)
        overlap = {r[0] for r in other.execute("select distinct path from nomenclature_binding")}

    rows = []
    for r in conn.execute("""
        select b.path, count(*) as bindings, min(b.binding_id) as first_binding
        from nomenclature_binding b group by b.path"""):
        path = r["path"]
        if path in catalogue:
            continue
        leaf = path.split(" > ")[-1]
        flag, is_flagged = classify(leaf)
        if not is_flagged:
            continue
        ev = conn.execute("""
            select b.verbatim, cl.coord, w.sheet_name, b.candidate_id, b.amount_role
            from nomenclature_binding b
            join cell cl on cl.cell_id = b.cell_id
            join worksheet w on w.worksheet_id = cl.worksheet_id
            where b.binding_id = ?""", (r["first_binding"],)).fetchone()
        target, action = recommend(leaf, " > ".join(path.split(" > ")[:-1]), catalogue,
                                   aliases.get(normalise(leaf), ""))
        rows.append({
            "action": action,
            "flag": flag,
            "leaf": leaf,
            "parent": " > ".join(path.split(" > ")[:-1]),
            "bindings": r["bindings"],
            "evidence": ev["verbatim"],
            "coord": ev["coord"],
            "sheet": ev["sheet_name"],
            "candidate_id": ev["candidate_id"],
            "role": ev["amount_role"],
            "recommended_category": target,
            "alias_existed": "yes" if normalise(leaf) in aliases else "no",
            "in_other_run": "yes" if path in overlap else "no",
            "decision": "",
        })

    rows.sort(key=lambda x: (x["action"], -x["bindings"]))
    args.out_dir.mkdir(parents=True, exist_ok=True)
    slug = args.run_label or args.db.stem

    fieldnames = [
        "action", "flag", "leaf", "parent", "bindings", "evidence", "coord", "sheet",
        "candidate_id", "role", "recommended_category", "alias_existed", "in_other_run",
        "decision",
    ]
    csv_path = args.out_dir / f"tier2-transcribed-leaves-{slug}.csv"
    with csv_path.open("w", newline="") as fh:
        writer = csv.DictWriter(fh, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(rows)

    counts: dict[str, int] = {}
    for row in rows:
        counts[row["action"]] = counts.get(row["action"], 0) + 1

    md = [f"# Tier 2 — transcribed soft leaves ({slug})", "",
          f"Policy: workbook text stays on the row as evidence; the row is filed under a real",
          f"category. Do not promote transcribed text into the catalogue.", "",
          f"Transcribed leaves: **{len(rows)}** · catalogue nodes: {len(catalogue)}", "",
          "| action | count |", "|---|---|"]
    for action, n in sorted(counts.items(), key=lambda kv: -kv[1]):
        md.append(f"| {action} | {n} |")
    shadowed = sum(1 for row in rows if row["alias_existed"] == "yes")
    md += [f"Alias for the identical text already existed and went unused: **{shadowed}**", ""]
    md += ["| action | flag | leaf | parent | sheet | coord | evidence | recommended category | alias existed | bindings | decision |",
           "|---|---|---|---|---|---|---|---|---|---|---|"]
    for row in rows:
        md.append("| {action} | {flag} | {leaf} | {parent} | {sheet} | {coord} | {evidence} | {rec} | {alias} | {n} | |".format(
            action=row["action"], flag=row["flag"], leaf=row["leaf"].replace("|", "\\|"),
            parent=row["parent"], sheet=row["sheet"], coord=row["coord"],
            evidence=str(row["evidence"]).replace("|", "\\|"),
            rec=row["recommended_category"] or "—", alias=row["alias_existed"], n=row["bindings"]))
    md_path = args.out_dir / f"tier2-transcribed-leaves-{slug}.md"
    md_path.write_text("\n".join(md) + "\n")

    print(f"{slug}: {len(rows)} transcribed leaves")
    for action, n in sorted(counts.items(), key=lambda kv: -kv[1]):
        print(f"   {action:<20} {n}")
    print(f"   written: {md_path}")
    print(f"            {csv_path}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
