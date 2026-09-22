#!/usr/bin/env python3
"""Independent validation of a persisted classify run, with no model and no old-run baseline.

Every check below reads only the SQLite workspace plus the workbook-derived tables the
pipeline itself wrote, so it cannot inherit a previous model's judgements:

  coverage_candidates  every Candidate has a packet_disposition
  coverage_cells       every Cell has a cell_interpretation
  provenance_labels    every binding verbatim exists as real text in its own row
  arithmetic           every deterministic aggregation balances: head == sum(sign * member)
  role_consistency     binding amount_role agrees with the cell interpretation
  catalogue_discipline binding paths outside the frozen nomenclature catalogue (soft leaves)
  soft_leaf_heuristics soft leaves that look like raw line items, not categories (RED)

Usage:
  python3 scripts/validate-classify-run.py --db path/to/workspace.db
  python3 scripts/validate-classify-run.py --db path/to/workspace.db --out report.json
  python3 scripts/validate-classify-run.py --db path/to/workspace.db --max-arithmetic-failures 5
Exit 0 = GREEN, 1 = RED, 2 = usage error.
"""
from __future__ import annotations

import argparse
import json
import re
import sqlite3
import sys
from pathlib import Path

SIGNS = {
    "plus": 1.0, "minus": -1.0, "add": 1.0, "deduct": -1.0,
    "1": 1.0, "-1": -1.0, "+": 1.0, "-": -1.0, "total": 1.0,
}

LINE_ITEM_PATTERNS = [
    re.compile(r"supplier", re.I),
    re.compile(r"^\s*less\s*[:\-]", re.I),
    re.compile(r"@\s*\d"),
    re.compile(r"\d+\s*(kva|kw|hp|mm|sq\.?\s*ft|mtr|meter|ft|ltr|kg|ton)", re.I),
    re.compile(r"\d+\s*%"),
    re.compile(r'"'),
    re.compile(r"ø"),
    re.compile(r"\bper\s+quotation\b", re.I),
    re.compile(r"\bas per (estimate|quotation)\b", re.I),
]


def connect(path: Path) -> sqlite3.Connection:
    conn = sqlite3.connect(f"file:{path}?mode=ro", uri=True)
    conn.row_factory = sqlite3.Row
    return conn


def scalar(conn: sqlite3.Connection, sql: str, params: tuple = ()) -> int:
    row = conn.execute(sql, params).fetchone()
    return int(row[0]) if row and row[0] is not None else 0


def has_table(conn: sqlite3.Connection, name: str) -> bool:
    return scalar(
        conn, "select count(*) from sqlite_master where type='table' and name=?", (name,)
    ) > 0


class Report:
    def __init__(self) -> None:
        self.checks: list[dict] = []

    def add(self, name, severity, failures, total, detail, samples=None):
        self.checks.append({
            "check": name,
            "severity": severity,
            "failures": failures,
            "total": total,
            "rate": (failures / total) if total else 0.0,
            "detail": detail,
            "samples": samples or [],
        })

    @property
    def red(self) -> bool:
        return any(c["severity"] == "RED" for c in self.checks)


def check_coverage(conn: sqlite3.Connection, rep: Report) -> None:
    total = scalar(conn, "select count(*) from candidate")
    fail = scalar(
        conn,
        """select count(*) from candidate c
           left join packet_disposition d on d.candidate_id = c.candidate_id
           where d.candidate_id is null""",
    )
    rep.add("coverage_candidates", "RED" if fail else "OK", fail, total,
            "Candidates without a packet_disposition")

    total_cells = scalar(conn, "select count(*) from cell")
    fail_cells = scalar(
        conn,
        """select count(*) from cell cl
           left join cell_interpretation i on i.cell_id = cl.cell_id
           where i.interpretation_id is null""",
    )
    rep.add("coverage_cells", "RED" if fail_cells else "OK", fail_cells, total_cells,
            "Cells without a cell_interpretation")


def check_provenance(conn: sqlite3.Connection, rep: Report) -> None:
    if not (has_table(conn, "nomenclature_binding") and has_table(conn, "cell")):
        return
    total = scalar(conn, "select count(*) from nomenclature_binding")
    fail = scalar(
        conn,
        """select count(*) from nomenclature_binding b
           join cell cl on cl.cell_id = b.cell_id
           where not exists (
             select 1 from cell lbl
             where lbl.worksheet_id = cl.worksheet_id
               and lbl.row_num = cl.row_num
               and trim(coalesce(lbl.text_value,'')) = trim(coalesce(b.verbatim,'')))""",
    )
    samples = [
        dict(verbatim=r["verbatim"], coord=r["coord"], path=r["path"])
        for r in conn.execute(
            """select b.verbatim, cl.coord, b.path from nomenclature_binding b
               join cell cl on cl.cell_id = b.cell_id
               where not exists (
                 select 1 from cell lbl
                 where lbl.worksheet_id = cl.worksheet_id
                   and lbl.row_num = cl.row_num
                   and trim(coalesce(lbl.text_value,'')) = trim(coalesce(b.verbatim,'')))
               limit 10"""
        )
    ]
    rep.add("provenance_labels", "RED" if fail else "OK", fail, total,
            "Bindings whose verbatim is not real text in its own row", samples)


FUNCTION_NAMES = re.compile(r"\b(SUM|SUBTOTAL|TOTAL|AVERAGE|IF|ROUND|ABS)\b", re.I)
OPERATOR_CHARS = re.compile(r"[\s()+\-*/,^&<>=:]")


def residual_formula(formula: str, member_coords: list[str]) -> str:
    """Head formula with every member cell reference removed.

    What survives is the head's own arithmetic: literal terms like ``*0.05`` and
    references to cells the aggregation never captured. Empty survivor means the
    head is a pure sum of its members, so a mismatch there is a real defect.
    """
    text = (formula or "").replace("$", "")
    for coord in member_coords:
        text = re.sub(r"(?<![A-Za-z0-9])" + re.escape(coord) + r"(?![0-9])", "", text)
    text = FUNCTION_NAMES.sub("", text)
    return OPERATOR_CHARS.sub("", text)


def check_arithmetic(conn: sqlite3.Connection, rep: Report, tolerance: int) -> None:
    if not (has_table(conn, "aggregation") and has_table(conn, "aggregation_member")):
        return
    checked = 0
    inline = 0
    unexplained = 0
    inline_samples = []
    unexplained_samples = []
    for agg in conn.execute("select aggregation_id, head_cell_id from aggregation"):
        head = conn.execute(
            "select numeric_value, formula_text from cell where cell_id=?",
            (agg["head_cell_id"],),
        ).fetchone()
        if head is None or head["numeric_value"] is None:
            continue
        total = 0.0
        usable = True
        coords = []
        for m in conn.execute(
            "select sign, member_cell_id from aggregation_member where aggregation_id=?",
            (agg["aggregation_id"],),
        ):
            mv = conn.execute(
                "select numeric_value, coord from cell where cell_id=?",
                (m["member_cell_id"],),
            ).fetchone()
            if mv is None or mv["numeric_value"] is None:
                usable = False
                break
            coords.append(mv["coord"])
            total += SIGNS.get(str(m["sign"]).strip().lower(), 0.0) * float(mv["numeric_value"])
        if not usable:
            continue
        checked += 1
        head_value = float(head["numeric_value"])
        if abs(total - head_value) <= max(1.0, abs(head_value) * 0.01):
            continue
        sample = dict(aggregation_id=agg["aggregation_id"], head=head_value,
                      members_total=total, diff=total - head_value)
        if residual_formula(head["formula_text"], coords):
            inline += 1
            sample["head_formula"] = head["formula_text"]
            if len(inline_samples) < 10:
                inline_samples.append(sample)
        else:
            unexplained += 1
            if len(unexplained_samples) < 10:
                unexplained_samples.append(sample)

    rep.add("arithmetic_head_inline_terms", "INFO", inline, checked,
            "Heads whose formula holds inline terms beyond member cells (not a balance error)",
            inline_samples)
    rep.add("arithmetic_unexplained", "RED" if unexplained > tolerance else "OK",
            unexplained, checked,
            f"Heads that are a pure sum of members yet do not balance (tolerance {tolerance})",
            unexplained_samples)


def check_role_consistency(conn: sqlite3.Connection, rep: Report) -> None:
    if not (has_table(conn, "nomenclature_binding") and has_table(conn, "cell_interpretation")):
        return
    total = scalar(conn, "select count(*) from nomenclature_binding")
    fail = scalar(
        conn,
        """select count(*) from nomenclature_binding b
           join cell_interpretation i on i.cell_id = b.cell_id
           where coalesce(b.amount_role,'') <> coalesce(i.amount_role,'')""",
    )
    rep.add("role_consistency", "RED" if fail else "OK", fail, total,
            "Bindings whose amount_role disagrees with the cell interpretation")


def check_catalogue(conn: sqlite3.Connection, rep: Report) -> None:
    if not has_table(conn, "nomenclature_node"):
        return
    catalogue = {r["path"] for r in conn.execute("select path from nomenclature_node")}
    total = scalar(conn, "select count(distinct path) from nomenclature_binding")
    outside = [
        r["path"] for r in conn.execute("select distinct path from nomenclature_binding")
        if r["path"] not in catalogue
    ]
    rep.add("catalogue_discipline", "INFO", len(outside), total,
            f"Distinct binding paths outside the {len(catalogue)}-node catalogue (soft leaves)",
            outside[:10])

    flagged = []
    for path in outside:
        leaf = path.split(" > ")[-1]
        if any(p.search(leaf) for p in LINE_ITEM_PATTERNS) or len(leaf) > 60:
            flagged.append(path)
    rep.add("soft_leaf_heuristics", "RED" if flagged else "OK", len(flagged),
            len(outside) if outside else 0,
            "Soft leaves that look like raw line items rather than categories", flagged[:10])


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--db", required=True, type=Path, help="workspace SQLite database (opened read-only)")
    ap.add_argument("--out", type=Path, help="write the report as JSON to this path")
    ap.add_argument("--max-arithmetic-failures", type=int, default=0,
                    help="aggregations allowed to miss reconciliation before RED (default 0)")
    args = ap.parse_args()

    if not args.db.is_file():
        print(f"no such database: {args.db}", file=sys.stderr)
        return 2

    conn = connect(args.db)
    rep = Report()
    check_coverage(conn, rep)
    check_provenance(conn, rep)
    check_arithmetic(conn, rep, args.max_arithmetic_failures)
    check_role_consistency(conn, rep)
    check_catalogue(conn, rep)

    width = max(len(c["check"]) for c in rep.checks)
    print(f"validate-classify-run  db={args.db}")
    print(f"{'check'.ljust(width)}  sev   failures/total   rate")
    for c in rep.checks:
        print(f"{c['check'].ljust(width)}  {c['severity']:<4}  {c['failures']:>8}/{c['total']:<7}  {c['rate']*100:5.1f}%")
        for s in c["samples"][:5]:
            print(f"{'':<{width}}        {s}")
    print()
    print("RED" if rep.red else "GREEN")

    if args.out:
        args.out.write_text(json.dumps(
            {"db": str(args.db), "result": "RED" if rep.red else "GREEN", "checks": rep.checks},
            indent=2, default=str))
        print(f"report written to {args.out}")

    return 1 if rep.red else 0


if __name__ == "__main__":
    sys.exit(main())
