# EvoObj object-construction experiment

Cluster scripting to compare **vanilla EvoSuite** against **EvoObj** (graph-based
seed object synthesis, `apply_object_rule`) and its enhancements, over a corpus
of classes. The EvoObj arms run in **whole-class mode** (no `-Dtarget_method`),
so the search picks an entry method per branch and the experiment fits a normal
per-class benchmark.

The layout mirrors the LLM experiment's `run_phaseN.py` / `submit_slurm.sh`:
a Python manifest generator emits chunked TSVs, and a SLURM array runs each chunk
inside the Apptainer image against `evosuite.jar`.

## Arms

| id | Description | Flags on top of the baseline |
|----|-------------|------------------------------|
| `baseline` | Vanilla EvoSuite | `apply_object_rule=false` |
| `evoobj` | EvoObj core | `apply_object_rule=true`, `object_rule_whole_class=true` |
| `evoobj_reflection` | + reflection fallback | `object_rule_reflection_fallback=true` |
| `evoobj_slotbias` | + mutation slot bias | `object_rule_slot_bias=true` |
| `evoobj_construction_fit` | + construction fitness | `construction_fitness=true` |
| `evoobj_full` | All enhancements | all three above |

Every arm holds all other search settings constant (DynaMOSA, MaxTime, the same
criteria including `METHODNOEXCEPTION` so `construction_fitness` has goals to
grade), so only the object-rule flags vary.

Config groups (`--config-group`): `core` (baseline + evoobj), `ablation` /
`full` (all six arms). Pick individual arms with `--config id1,id2` or the
`CONFIGS` env var.

## Corpus

A CSV with columns `project_id, class_name, java_path, complexity, classpath`
(see `data/classes.example.csv`). Only `project_id`, `class_name`, and
`classpath` are used; `java_path` and `complexity` are ignored (kept so the same
class list works across tools). Classpath entries are resolved relative to
`$SF110_ROOT/<project_id>/` unless already absolute. Copy your class list to
`data/classes.csv` (the default) or point `--corpus` / `CORPUS` elsewhere.

## Running

Inspect the matrix and run count without submitting:

```bash
cd experiments/objectconstruction/scripts
python3 run_experiment.py --list-configs
python3 run_experiment.py --corpus ../data/classes.csv --reps 10
```

Submit to SLURM (all knobs overridable via the environment):

```bash
REPS=10 SEARCH_BUDGET=120 CONFIG_GROUP=full \
  SLURM_ACCOUNT=chair-se2 \
  ./submit_slurm.sh
```

Key environment variables (defaults in parentheses):

- `CORPUS` (`../data/classes.csv`), `LIMIT_CLASSES` (all), `REPS` (10)
- `SEARCH_BUDGET` seconds (120), `SEED` (42; run seed is `SEED+rep`, `-1` disables)
- `CONFIG_GROUP` (`full`) or `CONFIGS` (comma-separated arm ids)
- `CHUNK_SIZE` runs per array task (5), `ARRAY_THROTTLE` concurrent tasks (8)
- `APPTAINER_IMAGE`, `EVOSUITE_JAR`, `DATASET_ROOT`/`SF110_ROOT`, `RUN_DIR`
- `SLURM_MEM` (6G), `SLURM_TIME` (24:00:00), `SLURM_ACCOUNT` (chair-se2),
  `SLURM_PARTITION` (general), `EVOSUITE_MEM_MB` (4000), `TIMEOUT_LIMIT` (30m)

Outputs land in `RUN_DIR`:

```
manifests/all_runs.tsv          full run list
manifests/chunks/chunk_*.tsv    one per array task
reports/<arm>/rep<N>/<class>/   statistics.csv + tests per run
logs/                           per-run EvoSuite stdout/stderr
status/task_<id>.tsv            exit code + attempts per run
slurm/                          SLURM stdout/stderr
```

## Aggregating

Once the array finishes:

```bash
python3 aggregate_results.py "$RUN_DIR" --out summary.csv
```

Prints, per arm, the run count and mean of `Coverage`, `BranchCoverage`,
`LineCoverage`, `MethodNoExceptionCoverage`, and the EvoObj statistics
(`ObjectGraphBranches`, `ObjectGraphMaxSize`, `TemplateSynthesisTime`,
`TemplateInjections`, `LegitimizationSuccessRate`).
