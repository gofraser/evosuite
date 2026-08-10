#!/usr/bin/env python3
"""Aggregate a finished object-construction run into a failure-aware summary.

Reports planned runs, process-success rows, statistics files, validated usable
runs, and the subset paired across every arm.  Metric means include only rows
whose final generation status is successful and whose coverage is numeric.

Usage:
    python3 aggregate_results.py <run_dir> [--out summary.csv]
"""

import argparse
import csv
import os
import statistics
from collections import Counter, defaultdict

import experiment_utils
import validate_statistics

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


def _read_final_row(path):
    """Return the last complete statistics row for one manifest run."""
    with open(path, newline="") as f:
        rows = list(csv.DictReader(f))
    return rows[-1] if rows else None


def _load_final_statuses(run_dir):
    """Load the final status row per run id."""
    status_dir = os.path.join(run_dir, "status")
    if not os.path.isdir(status_dir):
        return {}
    final_by_run_id = {}
    for name in sorted(os.listdir(status_dir)):
        if not name.endswith(".tsv"):
            continue
        with open(os.path.join(status_dir, name), newline="") as f:
            for row in csv.DictReader(f, delimiter="\t"):
                final_by_run_id[row["run_id"]] = row
    return final_by_run_id


def _status_run_key(row):
    return (
        row["config_id"],
        f'rep{row["repetition"]}',
        experiment_utils.sanitize_class_segment(row["class_name"]),
    )


def _load_successful_run_keys(run_dir):
    """Load process-success keys; retained as a small public helper for tests."""
    final_by_run_id = _load_final_statuses(run_dir)
    if not final_by_run_id:
        return None
    return {
        _status_run_key(row)
        for row in final_by_run_id.values()
        if row.get("exit_code") == "0"
    }


def _load_manifest_rows(run_dir):
    path = os.path.join(run_dir, "manifests", "all_runs.tsv")
    if not os.path.isfile(path):
        return []
    with open(path, newline="") as source:
        return list(csv.DictReader(source, delimiter="\t"))


def _manifest_run_key(row):
    return (
        row["config_id"],
        f'rep{row["repetition"]}',
        experiment_utils.sanitize_class_segment(row["class_name"]),
    )


def _logical_run_key(row):
    return row["project_id"], row["class_name"], row["repetition"]


def _run_key(reports_dir, arm, path):
    relative_parts = os.path.relpath(os.path.dirname(path), reports_dir).split(os.sep)
    if len(relative_parts) < 3:
        return None
    return arm, relative_parts[1], relative_parts[2]


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


def _statistics_validity(row):
    """Validate new rows while accepting numeric legacy rows without a status column."""
    if row is None:
        return False, "missing_statistics"
    status = _lookup(row, "Test_Generation_Status")
    if status is None:
        return (_to_float(_lookup(row, "Coverage")) is not None,
                "success" if _to_float(_lookup(row, "Coverage")) is not None
                else "coverage_unavailable")
    return validate_statistics.validate_row(row)


def _paired_counts(manifest_rows, usable_keys):
    if not manifest_rows:
        return "", ""
    configs = sorted({row["config_id"] for row in manifest_rows})
    planned_by_logical = defaultdict(dict)
    repetitions_by_class = defaultdict(set)
    for row in manifest_rows:
        logical = _logical_run_key(row)
        planned_by_logical[logical][row["config_id"]] = _manifest_run_key(row)
        repetitions_by_class[(row["project_id"], row["class_name"])].add(row["repetition"])

    paired_logical = {
        logical for logical, by_config in planned_by_logical.items()
        if sorted(by_config) == configs and all(key in usable_keys for key in by_config.values())
    }
    paired_repetitions_by_class = Counter((project, class_name)
                                          for project, class_name, _rep in paired_logical)
    fully_paired_classes = sum(
        paired_repetitions_by_class[class_key] == len(repetitions)
        for class_key, repetitions in repetitions_by_class.items()
    )
    return len(paired_logical), fully_paired_classes


def _pairwise_exclusions(manifest_rows, usable_keys, reason_by_key):
    """Return one record for each repetition excluded from an all-arm comparison."""
    configs = sorted({row["config_id"] for row in manifest_rows})
    planned_by_logical = defaultdict(dict)
    for row in manifest_rows:
        planned_by_logical[_logical_run_key(row)][row["config_id"]] = _manifest_run_key(row)

    exclusions = []
    for logical, by_config in sorted(planned_by_logical.items()):
        failures = []
        for config in configs:
            key = by_config.get(config)
            if key is None:
                failures.append(f"{config}:not_planned")
            elif key not in usable_keys:
                failures.append(f"{config}:{reason_by_key.get(key, 'unusable')}")
        if failures:
            exclusions.append({
                "project_id": logical[0],
                "class_name": logical[1],
                "repetition": logical[2],
                "excluded_configs": ";".join(failures),
            })
    return exclusions


def _terminal_problems(manifest_rows, final_statuses, statistics_by_key):
    """Find rows that have not reached a trustworthy terminal state.

    A non-zero status is terminal and remains missing data. A zero status is terminal only when
    its statistics row validates. This distinction lets a completed experiment contain uniformly
    excluded failures without mistaking a partial/invalid success for zero coverage.
    """
    problems = []
    manifest_ids = {row["run_id"] for row in manifest_rows}
    for row in manifest_rows:
        status = final_statuses.get(row["run_id"])
        if status is None:
            problems.append((row["run_id"], "missing_status"))
            continue
        if status.get("exit_code") != "0":
            continue
        key = _manifest_run_key(row)
        valid, reason = _statistics_validity(statistics_by_key.get(key))
        if not valid:
            problems.append((row["run_id"], f"successful_process_{reason}"))
    for run_id in sorted(set(final_statuses).difference(manifest_ids)):
        problems.append((run_id, "status_not_in_manifest"))
    return problems


def main():
    parser = argparse.ArgumentParser(description="Aggregate an object-construction run.")
    parser.add_argument("run_dir", help="Run directory produced by submit_slurm.sh.")
    parser.add_argument("--out", default=None, help="Optional CSV path for the summary.")
    parser.add_argument("--require-complete", action="store_true",
                        help="Exit non-zero unless every planned run is validated and paired across arms.")
    parser.add_argument("--require-finished", action="store_true",
                        help="Exit non-zero unless every planned run has a trustworthy terminal status.")
    parser.add_argument("--pairwise-exclusions", default=None,
                        help="Optional CSV describing repetitions excluded from all-arm comparisons.")
    args = parser.parse_args()

    reports_dir = os.path.join(args.run_dir, "reports")
    manifest_rows = _load_manifest_rows(args.run_dir)
    final_statuses = _load_final_statuses(args.run_dir)
    status_by_key = {_status_run_key(row): row for row in final_statuses.values()}
    statistics_by_key = {}
    for arm, path in _find_statistics_files(reports_dir):
        statistics_by_key[_run_key(reports_dir, arm, path)] = _read_final_row(path)

    configs = sorted(
        {row["config_id"] for row in manifest_rows}
        | {key[0] for key in status_by_key}
        | {key[0] for key in statistics_by_key}
    )
    per_arm = {
        arm: {
            "planned_runs": 0,
            "status_success_runs": 0,
            "statistics_files": 0,
            "usable_runs": 0,
            "failure_reasons": Counter(),
            "values": {col: [] for col in _SUMMARY_COLS},
        }
        for arm in configs
    }

    planned_keys = {_manifest_run_key(row) for row in manifest_rows}
    all_keys = planned_keys | set(status_by_key) | set(statistics_by_key)
    usable_keys = set()
    reason_by_key = {}
    for key in all_keys:
        arm = key[0]
        bucket = per_arm.setdefault(arm, {
            "planned_runs": 0, "status_success_runs": 0, "statistics_files": 0,
            "usable_runs": 0, "failure_reasons": Counter(),
            "values": {col: [] for col in _SUMMARY_COLS},
        })
        if key in planned_keys:
            bucket["planned_runs"] += 1
        status = status_by_key.get(key)
        process_success = status is None or status.get("exit_code") == "0"
        if status is not None and process_success:
            bucket["status_success_runs"] += 1
        row = statistics_by_key.get(key)
        if key in statistics_by_key:
            bucket["statistics_files"] += 1
        valid, reason = _statistics_validity(row)
        if not process_success:
            reason = status.get("failure_reason") or f'process_exit_{status.get("exit_code", "unknown")}'
            valid = False
        if not valid:
            bucket["failure_reasons"][reason] += 1
            reason_by_key[key] = reason
            continue
        usable_keys.add(key)
        reason_by_key[key] = "success"
        bucket["usable_runs"] += 1
        for col in _SUMMARY_COLS:
            value = _to_float(_lookup(row, col))
            if value is not None:
                bucket["values"][col].append(value)

    if not per_arm:
        print(f"No statistics.csv found under {reports_dir}")
        return

    paired_runs, fully_paired_classes = _paired_counts(manifest_rows, usable_keys)
    count_cols = [
        "planned_runs", "status_success_runs", "statistics_files", "usable_runs",
        "failed_runs", "paired_runs", "fully_paired_classes", "failure_reasons",
    ]
    header = ["config_id"] + count_cols + [f"mean_{c}" for c in _SUMMARY_COLS]
    summary_rows = []
    for arm in sorted(per_arm):
        bucket = per_arm[arm]
        planned = bucket["planned_runs"] or len([key for key in all_keys if key[0] == arm])
        reasons = ";".join(f"{reason}={count}" for reason, count
                           in sorted(bucket["failure_reasons"].items()))
        row = [
            arm,
            planned,
            bucket["status_success_runs"] if final_statuses else "",
            bucket["statistics_files"],
            bucket["usable_runs"],
            planned - bucket["usable_runs"],
            paired_runs,
            fully_paired_classes,
            reasons,
        ]
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

    exclusions = _pairwise_exclusions(manifest_rows, usable_keys, reason_by_key)
    if args.pairwise_exclusions:
        with open(args.pairwise_exclusions, "w", newline="") as destination:
            writer = csv.DictWriter(destination, fieldnames=[
                "project_id", "class_name", "repetition", "excluded_configs"])
            writer.writeheader()
            writer.writerows(exclusions)
        print(f"\nPairwise exclusions written to {args.pairwise_exclusions}")

    if args.require_finished:
        terminal_problems = _terminal_problems(
            manifest_rows, final_statuses, statistics_by_key)
        if terminal_problems:
            formatted = "; ".join(f"{run_id}:{reason}" for run_id, reason
                                  in terminal_problems[:20])
            print(f"\nRun has {len(terminal_problems)} non-terminal row(s): {formatted}")
            raise SystemExit(3)
        print(f"\nRun is terminal: {len(manifest_rows)} planned rows classified; "
              f"{len(exclusions)} repetition(s) excluded pairwise.")

    if args.require_complete:
        incomplete = any(
            (bucket["planned_runs"] or len([key for key in all_keys if key[0] == arm]))
            != bucket["usable_runs"]
            for arm, bucket in per_arm.items()
        )
        planned_per_arm = {
            bucket["planned_runs"] or len([key for key in all_keys if key[0] == arm])
            for arm, bucket in per_arm.items()
        }
        if incomplete or len(planned_per_arm) != 1 or (planned_per_arm and paired_runs != next(iter(planned_per_arm))):
            print("\nRun is incomplete or not fully paired across configurations.")
            raise SystemExit(2)
        print("\nRun is complete and fully paired across configurations.")


if __name__ == "__main__":
    main()
