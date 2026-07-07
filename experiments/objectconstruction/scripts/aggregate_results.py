#!/usr/bin/env python3
"""Aggregate a finished object-construction run into a per-arm summary.

Walks ``<run_dir>/reports/<arm>/rep<N>/<class>/statistics.csv`` (the layout the
array runner produces) and reports, per arm, the mean coverage and the mean of
each EvoObj statistic, plus the run count. Coverage columns are matched
case-insensitively so it works whether the schema names them ``Coverage`` /
``BranchCoverage`` or lowercase variants.

Usage:
    python3 aggregate_results.py <run_dir> [--out summary.csv]
"""

import argparse
import csv
import os
import statistics

import experiment_utils

_COVERAGE_COLS = ["Coverage", "BranchCoverage", "LineCoverage", "MethodNoExceptionCoverage"]
_OBJECT_RULE_COLS = list(experiment_utils.OBJECT_RULE_OUTPUT_VARIABLES)
_SUMMARY_COLS = _COVERAGE_COLS + _OBJECT_RULE_COLS


def _find_statistics_files(reports_dir):
    """Yield (arm, path) for every statistics.csv under reports/<arm>/..."""
    if not os.path.isdir(reports_dir):
        return
    for arm in sorted(os.listdir(reports_dir)):
        arm_dir = os.path.join(reports_dir, arm)
        if not os.path.isdir(arm_dir):
            continue
        for root, _dirs, files in os.walk(arm_dir):
            if "statistics.csv" in files:
                yield arm, os.path.join(root, "statistics.csv")


def _read_rows(path):
    with open(path, newline="") as f:
        return list(csv.DictReader(f))


def _lookup(row, wanted):
    """Case-insensitive column lookup; returns the string value or None."""
    for key, value in row.items():
        if key is not None and key.lower() == wanted.lower():
            return value
    return None


def _to_float(value):
    if value is None or value == "":
        return None
    try:
        return float(value)
    except ValueError:
        return None


def main():
    parser = argparse.ArgumentParser(description="Aggregate an object-construction run.")
    parser.add_argument("run_dir", help="Run directory produced by submit_slurm.sh.")
    parser.add_argument("--out", default=None, help="Optional CSV path for the summary.")
    args = parser.parse_args()

    reports_dir = os.path.join(args.run_dir, "reports")
    per_arm = {}
    for arm, path in _find_statistics_files(reports_dir):
        for row in _read_rows(path):
            bucket = per_arm.setdefault(arm, {"runs": 0, "values": {c: [] for c in _SUMMARY_COLS}})
            bucket["runs"] += 1
            for col in _SUMMARY_COLS:
                v = _to_float(_lookup(row, col))
                if v is not None:
                    bucket["values"][col].append(v)

    if not per_arm:
        print(f"No statistics.csv found under {reports_dir}")
        return

    header = ["config_id", "runs"] + [f"mean_{c}" for c in _SUMMARY_COLS]
    summary_rows = []
    for arm in sorted(per_arm):
        bucket = per_arm[arm]
        row = [arm, bucket["runs"]]
        for col in _SUMMARY_COLS:
            vals = bucket["values"][col]
            row.append(round(statistics.fmean(vals), 4) if vals else "")
        summary_rows.append(row)

    widths = [max(len(str(r[i])) for r in ([header] + summary_rows)) for i in range(len(header))]
    for r in [header] + summary_rows:
        print("  ".join(str(cell).ljust(widths[i]) for i, cell in enumerate(r)))

    if args.out:
        with open(args.out, "w", newline="") as f:
            writer = csv.writer(f)
            writer.writerow(header)
            writer.writerows(summary_rows)
        print(f"\nSummary written to {args.out}")


if __name__ == "__main__":
    main()
