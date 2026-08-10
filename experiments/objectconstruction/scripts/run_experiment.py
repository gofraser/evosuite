#!/usr/bin/env python3
"""Generate manifests for the frozen EvoObj experiment."""

import argparse
import csv
import math
import os

import experiment_utils

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
EXPERIMENT_ROOT = os.path.abspath(os.path.join(SCRIPT_DIR, ".."))
DEFAULT_DATASET_ROOT = os.environ.get(
    "DATASET_ROOT",
    os.environ.get("SF110_ROOT", "/scratch/fraser/sf110-with-dependencies"),
)
DEFAULT_CORPUS = os.path.join(EXPERIMENT_ROOT, "data", "classes.csv")


BASELINE_ARM = {
    "id": "baseline",
    "name": "Vanilla EvoSuite (EvoObj disabled)",
    "params": ["-Dapply_object_rule=false"],
}

# The cleaned implementation has one supported EvoObj switch. The application probability remains
# explicit so archived manifests document the stochastic comparison precisely.
EVOOBJ_SYNTHESIS_FINAL_ARM = {
    "id": "evoobj_synthesis_final",
    "name": "EvoObj whole-class synthesis",
    "params": [
        "-Dapply_object_rule=true",
        "-Dp_object_rule_application=0.6",
    ],
}

FINAL_CONFIGS = [BASELINE_ARM, EVOOBJ_SYNTHESIS_FINAL_ARM]
CONFIG_GROUPS = {
    "final": FINAL_CONFIGS,
    "main": FINAL_CONFIGS,
    "finalists": FINAL_CONFIGS,
}
ALL_CONFIGS = FINAL_CONFIGS


def _select_configs(config_names, config_group):
    configs = CONFIG_GROUPS[config_group]
    if not config_names:
        return configs

    by_id = {config["id"]: config for config in ALL_CONFIGS}
    selected = []
    unknown = []
    for raw in config_names:
        for name in raw.split(","):
            name = name.strip()
            if not name:
                continue
            config = by_id.get(name)
            if config is None:
                unknown.append(name)
            elif config not in selected:
                selected.append(config)
    if unknown:
        known = ", ".join(config["id"] for config in ALL_CONFIGS)
        raise ValueError(f"Unknown config(s): {', '.join(unknown)}. Known ids: {known}")
    return selected


def _absolute_classpath(project_id, classpath, dataset_root):
    """Resolve a colon-separated corpus classpath against its project directory."""
    return ":".join(
        part if os.path.isabs(part)
        else os.path.abspath(os.path.join(dataset_root, project_id, part))
        for part in classpath.split(":") if part
    )


def build_extra_args(config, project_id, class_name, repetition, search_budget, seed):
    class_segment = experiment_utils.sanitize_class_segment(class_name)
    report_dir = f"/results/reports/{config['id']}/rep{repetition}/{class_segment}"
    test_dir = f"{report_dir}/tests"
    seed_args = [] if seed is None else [f"-Drandom_seed={int(seed) + repetition}"]
    extra_args = experiment_utils.merge_evosuite_args(
        experiment_utils.get_standard_evosuite_args(search_budget),
        seed_args,
        config["params"],
        experiment_utils.get_output_metadata_args(config["id"], project_id),
        [f"-Dreport_dir={report_dir}", f"-Dtest_dir={test_dir}"],
    )
    return " ".join(extra_args)


def create_parser():
    parser = argparse.ArgumentParser(
        description="Generate the frozen EvoObj object-construction experiment manifest.")
    parser.add_argument("--corpus", default=DEFAULT_CORPUS,
                        help="Corpus CSV (project_id, class_name, classpath).")
    parser.add_argument("--dataset-root", default=DEFAULT_DATASET_ROOT,
                        help="Root containing each project directory.")
    parser.add_argument("--limit-classes", type=int, default=None)
    parser.add_argument("--reps", type=int, default=10)
    parser.add_argument("--chunk-size", type=int, default=5)
    parser.add_argument("--search-budget", type=int, default=120)
    parser.add_argument("--seed", type=int, default=42,
                        help="Base seed; each repetition adds its index. Use -1 for no fixed seed.")
    parser.add_argument("--config", action="append", default=[],
                        help="Arm id; repeat or use comma-separated ids.")
    parser.add_argument("--config-group", choices=sorted(CONFIG_GROUPS), default="final",
                        help="Arm set. 'final', 'main', and 'finalists' are the frozen pair.")
    parser.add_argument("--generate-manifest", default=None)
    parser.add_argument("--list-configs", action="store_true")
    return parser


def main():
    args = create_parser().parse_args()
    seed = None if args.seed is not None and args.seed < 0 else args.seed
    selected_configs = _select_configs(args.config, args.config_group)

    if args.list_configs:
        for config in selected_configs:
            print(f"{config['id']:>30}  {config['name']}")
            print(f"{'':>30}  {' '.join(config['params'])}")
        return

    classes = experiment_utils.load_classes(args.corpus, args.limit_classes)
    if classes is None:
        print(f"Corpus CSV not found: {args.corpus}")
        return
    if not classes:
        print(f"Corpus CSV has no rows: {args.corpus}")
        return
    if not args.generate_manifest:
        runs = len(selected_configs) * len(classes) * args.reps
        print(f"{len(selected_configs)} arms x {len(classes)} classes x "
              f"{args.reps} reps = {runs} runs.")
        print("Pass --generate-manifest <dir> to write the manifest and chunks.")
        return

    manifest_dir = os.path.join(args.generate_manifest, "manifests")
    chunks_dir = os.path.join(manifest_dir, "chunks")
    os.makedirs(chunks_dir, exist_ok=True)
    header = ["run_id", "project_id", "class_name", "classpath",
              "config_id", "repetition", "extra_args"]
    all_runs = []
    for config in selected_configs:
        for row in classes:
            classpath = _absolute_classpath(
                row["project_id"], row["classpath"], args.dataset_root)
            for repetition in range(args.reps):
                all_runs.append([
                    len(all_runs), row["project_id"], row["class_name"], classpath,
                    config["id"], repetition,
                    build_extra_args(config, row["project_id"], row["class_name"],
                                     repetition, args.search_budget, seed),
                ])

    with open(os.path.join(manifest_dir, "all_runs.tsv"), "w", newline="") as destination:
        writer = csv.writer(destination, delimiter="\t")
        writer.writerow(header)
        writer.writerows(all_runs)

    chunk_count = math.ceil(len(all_runs) / args.chunk_size)
    for chunk_id in range(chunk_count):
        start = chunk_id * args.chunk_size
        end = min(start + args.chunk_size, len(all_runs))
        chunk = os.path.join(chunks_dir, f"chunk_{chunk_id:05d}.tsv")
        with open(chunk, "w", newline="") as destination:
            writer = csv.writer(destination, delimiter="\t")
            writer.writerow(header)
            writer.writerows(all_runs[start:end])
    print(f"Manifests written to {manifest_dir}. "
          f"Total runs: {len(all_runs)}, Chunks: {chunk_count}")


if __name__ == "__main__":
    main()
