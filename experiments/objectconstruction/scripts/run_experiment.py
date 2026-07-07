#!/usr/bin/env python3
"""Generate the run manifest for the EvoObj object-construction experiment.

Compares vanilla EvoSuite (``apply_object_rule=false``) against the EvoObj
graph-based seed object synthesis and its enhancements, in whole-class mode
(no ``-Dtarget_method``, so it fits a per-class benchmark). Mirrors the
manifest/chunk layout of the LLM experiment's ``run_phaseN.py`` so the same
SLURM array runner can drive it.

Typical use is via ``submit_slurm.sh``, which calls this with
``--generate-manifest`` and then submits the chunked array. It can also be run
directly to inspect the arm matrix or emit a manifest locally.
"""

import argparse
import csv
import math
import os

import experiment_utils

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
EXPERIMENT_ROOT = os.path.abspath(os.path.join(SCRIPT_DIR, ".."))

# Host root of the SF110-with-dependencies dataset. Classpaths in the corpus CSV
# are resolved relative to this when they are not already absolute.
SF110_ROOT = os.environ.get("SF110_ROOT", "/scratch/fraser/sf110-with-dependencies")

DEFAULT_CORPUS = os.path.join(EXPERIMENT_ROOT, "data", "classes.csv")


# --- Arm matrix -----------------------------------------------------------
#
# Every EvoObj arm runs in whole-class mode. Each arm lists all four object-rule
# switches explicitly (true/false) so the emitted command is unambiguous and the
# arms differ only in the intended flag(s). The baseline turns the rule off.

_ENHANCEMENT_FLAGS = (
    "reflection_fallback",
    "slot_bias",
    "construction_fitness",
    "structural_legitimization",
)


def _object_rule_arm(enabled_enhancements, legitimization=True):
    """Whole-class EvoObj arm with the named enhancements toggled on.

    legitimization is set explicitly (not left to the Properties default, which is now false) so an
    arm's behaviour is pinned regardless of the client default.
    """
    params = [
        "-Dapply_object_rule=true",
        "-Dobject_rule_whole_class=true",
        f"-Dobject_rule_legitimization={'true' if legitimization else 'false'}",
    ]
    for flag in _ENHANCEMENT_FLAGS:
        value = "true" if flag in enabled_enhancements else "false"
        # construction_fitness is the one enhancement without an object_rule_ prefix.
        key = flag if flag == "construction_fitness" else f"object_rule_{flag}"
        params.append(f"-D{key}={value}")
    return params


BASELINE_ARM = {
    "id": "baseline",
    "name": "Vanilla EvoSuite (object rule off)",
    "params": [
        "-Dapply_object_rule=false",
        "-Dobject_rule_whole_class=false",
        "-Dobject_rule_reflection_fallback=false",
        "-Dobject_rule_slot_bias=false",
        "-Dconstruction_fitness=false",
    ],
}

EVOOBJ_ARM = {
    "id": "evoobj",
    "name": "EvoObj core (whole-class, no enhancements)",
    "params": _object_rule_arm(set()),
}

EVOOBJ_REFLECTION_ARM = {
    "id": "evoobj_reflection",
    "name": "EvoObj + reflection fallback",
    "params": _object_rule_arm({"reflection_fallback"}),
}

EVOOBJ_SLOTBIAS_ARM = {
    "id": "evoobj_slotbias",
    "name": "EvoObj + mutation slot bias",
    "params": _object_rule_arm({"slot_bias"}),
}

EVOOBJ_CONSTRUCTION_FIT_ARM = {
    "id": "evoobj_construction_fit",
    "name": "EvoObj + construction fitness (METHODNOEXCEPTION distance)",
    "params": _object_rule_arm({"construction_fitness"}),
}

EVOOBJ_STRUCTURAL_ARM = {
    "id": "evoobj_structural",
    "name": "EvoObj + structural legitimization (construction-path mutation)",
    "params": _object_rule_arm({"structural_legitimization"}),
}

EVOOBJ_FULL_ARM = {
    "id": "evoobj_full",
    "name": "EvoObj + all enhancements",
    "params": _object_rule_arm(set(_ENHANCEMENT_FLAGS)),
}

# Construction fitness alone: the METHODNOEXCEPTION grading (E9) with NO object construction —
# no synthesis, no legitimizer. Isolates the outer-GA effect of the graded distance.
CONSTRUCTION_FIT_ONLY_ARM = {
    "id": "construction_fit_only",
    "name": "Construction fitness only (no object rule)",
    "params": [
        "-Dapply_object_rule=false",
        "-Dobject_rule_whole_class=false",
        "-Dobject_rule_reflection_fallback=false",
        "-Dobject_rule_slot_bias=false",
        "-Dconstruction_fitness=true",
    ],
}

# Synthesis only: inject synthesized templates but never run the legitimizer to repair throwing
# ones. Isolates pure-synthesis cost/effect from legitimization overhead.
EVOOBJ_NOLEGIT_ARM = {
    "id": "evoobj_nolegit",
    "name": "EvoObj synthesis only (legitimizer off)",
    "params": _object_rule_arm(set(), legitimization=False),
}

# Synthesis + construction fitness, still no legitimizer.
EVOOBJ_NOLEGIT_CF_ARM = {
    "id": "evoobj_nolegit_cf",
    "name": "EvoObj synthesis + construction fitness (legitimizer off)",
    "params": _object_rule_arm({"construction_fitness"}, legitimization=False),
}

CORE_CONFIGS = [BASELINE_ARM, EVOOBJ_ARM]
ABLATION_CONFIGS = [
    EVOOBJ_REFLECTION_ARM,
    EVOOBJ_SLOTBIAS_ARM,
    EVOOBJ_CONSTRUCTION_FIT_ARM,
    EVOOBJ_STRUCTURAL_ARM,
    EVOOBJ_FULL_ARM,
    CONSTRUCTION_FIT_ONLY_ARM,
    EVOOBJ_NOLEGIT_ARM,
    EVOOBJ_NOLEGIT_CF_ARM,
]

CONFIG_GROUPS = {
    "core": CORE_CONFIGS,
    "ablation": CORE_CONFIGS + ABLATION_CONFIGS,
    "full": CORE_CONFIGS + ABLATION_CONFIGS,
}

ALL_CONFIGS = CORE_CONFIGS + ABLATION_CONFIGS


def _select_configs(config_names, config_group):
    configs = CONFIG_GROUPS[config_group]
    if not config_names:
        return configs

    by_id = {cfg["id"]: cfg for cfg in ALL_CONFIGS}
    selected = []
    unknown = []
    for raw in config_names:
        for name in raw.split(","):
            name = name.strip()
            if not name:
                continue
            cfg = by_id.get(name)
            if cfg is None:
                unknown.append(name)
            elif cfg not in selected:
                selected.append(cfg)
    if unknown:
        known = ", ".join(cfg["id"] for cfg in ALL_CONFIGS)
        raise ValueError(f"Unknown config(s): {', '.join(unknown)}. Known ids: {known}")
    return selected


def _absolute_classpath(project_id, classpath):
    parts = classpath.split(":")
    abs_parts = [
        p if os.path.isabs(p) else os.path.abspath(os.path.join(SF110_ROOT, project_id, p))
        for p in parts
    ]
    return ":".join(abs_parts)


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
        description="Generate the EvoObj object-construction experiment manifest.")
    parser.add_argument("--corpus", type=str, default=DEFAULT_CORPUS,
                        help="Corpus CSV (columns: project_id, class_name, classpath).")
    parser.add_argument("--limit-classes", type=int, default=None,
                        help="Cap on the number of classes (default: all rows).")
    parser.add_argument("--reps", type=int, default=10, help="Repetitions per (arm, class).")
    parser.add_argument("--chunk-size", type=int, default=5, help="Runs per array-task chunk.")
    parser.add_argument("--search-budget", type=int, default=120,
                        help="Per-run search budget in seconds.")
    parser.add_argument("--seed", type=int, default=42,
                        help="Base random seed; run seed is seed+repetition. Use -1 for no fixed seed.")
    parser.add_argument("--config", action="append", default=[],
                        help="Arm id to include. Repeatable or comma-separated.")
    parser.add_argument("--config-group", choices=sorted(CONFIG_GROUPS), default="full",
                        help="Arm set to run. Default 'full' = baseline + evoobj + 4 ablation arms.")
    parser.add_argument("--generate-manifest", type=str, default=None,
                        help="Output directory for the manifest and chunk TSVs.")
    parser.add_argument("--list-configs", action="store_true",
                        help="Print the selected arm matrix and exit.")
    return parser


def main():
    parser = create_parser()
    args = parser.parse_args()

    seed = None if args.seed is not None and args.seed < 0 else args.seed
    selected_configs = _select_configs(args.config, args.config_group)

    if args.list_configs:
        for cfg in selected_configs:
            print(f"{cfg['id']:>26}  {cfg['name']}")
            print(f"{'':>26}  {' '.join(cfg['params'])}")
        return

    classes = experiment_utils.load_classes(args.corpus, args.limit_classes)
    if classes is None:
        print(f"Corpus CSV not found: {args.corpus}")
        return
    if not classes:
        print(f"Corpus CSV has no rows: {args.corpus}")
        return

    if not args.generate_manifest:
        print(f"{len(selected_configs)} arms x {len(classes)} classes x {args.reps} reps = "
              f"{len(selected_configs) * len(classes) * args.reps} runs.")
        print("Pass --generate-manifest <dir> to write the manifest and chunks.")
        return

    manifest_dir = os.path.join(args.generate_manifest, "manifests")
    chunks_dir = os.path.join(manifest_dir, "chunks")
    os.makedirs(chunks_dir, exist_ok=True)

    header = ["run_id", "project_id", "class_name", "classpath",
              "config_id", "repetition", "extra_args"]
    all_runs = []
    run_id = 0
    for config in selected_configs:
        for row in classes:
            full_classpath = _absolute_classpath(row["project_id"], row["classpath"])
            for r in range(args.reps):
                args_str = build_extra_args(
                    config, row["project_id"], row["class_name"], r, args.search_budget, seed)
                all_runs.append([
                    run_id, row["project_id"], row["class_name"], full_classpath,
                    config["id"], r, args_str,
                ])
                run_id += 1

    with open(os.path.join(manifest_dir, "all_runs.tsv"), "w", newline="") as f:
        writer = csv.writer(f, delimiter="\t")
        writer.writerow(header)
        writer.writerows(all_runs)

    chunk_count = math.ceil(len(all_runs) / args.chunk_size)
    for chunk_id in range(chunk_count):
        start = chunk_id * args.chunk_size
        end = min((chunk_id + 1) * args.chunk_size, len(all_runs))
        with open(os.path.join(chunks_dir, f"chunk_{chunk_id:05d}.tsv"), "w", newline="") as f:
            writer = csv.writer(f, delimiter="\t")
            writer.writerow(header)
            writer.writerows(all_runs[start:end])

    print(f"Manifests written to {manifest_dir}. Total runs: {len(all_runs)}, Chunks: {chunk_count}")


if __name__ == "__main__":
    main()
