# EvoObj object-construction experiment

EvoObj adds graph-based seed-object synthesis to EvoSuite. The frozen experiment compares vanilla
EvoSuite with one production configuration; historical tuning mechanisms have been removed from
the runner.

## Frozen configurations

| ID | Purpose |
|---|---|
| `baseline` | Vanilla EvoSuite (`apply_object_rule=false`) |
| `evoobj_synthesis_final` | The cleaned whole-class synthesis pipeline |

`final`, `main`, and `finalists` are aliases containing exactly the baseline and cleaned finalist.
Forward experiments should use `final`.

The finalist pins all behavior-bearing properties explicitly:

```text
apply_object_rule=true
p_object_rule_application=0.6
```

## Synthesis behavior

An eligible graph belongs to an uncovered branch whose reachable entry is callable and whose
rooted dependency graph contains state that synthesis can influence. Whole-class eligibility and
entry discovery occur before the effective search budget. Constructors and reachable static
factories are valid expanded entries. Invalid operand links, inaccessible entries, empty relevant
state, and graphs that cannot be rooted at a constructable input are rejected with a stable reason.

A synthesis attempt is one fresh graph-to-test conversion for a selected eligible branch. A
template injection occurs only when a consistent synthesized test (or an exact-branch cached clone)
is handed to the search. Failed, inconsistent, or unchanged speculative templates are not
injections.

Speculation snapshots the random stream and temporary generation state. If eligibility,
construction, synthesis, or consistency checks reject the candidate, EvoSuite restores that state
and performs ordinary random generation. Consequently `apply_object_rule=false`, a zero
application probability, and rejected EvoObj attempts preserve ordinary generation behavior.

Functional mocks are used only when normal construction cannot provide the required value.
After the template's normal first execution, EvoObj may materialize observed missing return stubs
on a clone. Retry and extra-execution bounds are private implementation policy, and unsuccessful
recipes are negatively cached while successful recipes may be proactively reused.

EvoObj resets its graph context, branch selector, template cache, functional-mock recipe caches,
and counters at generation boundaries. The run-scoped runtime is owned by `TestGenerationContext`.

## Corpus

The corpus CSV has `project_id`, `class_name`, and `classpath` columns. Optional `java_path` and
`complexity` columns are ignored. Relative classpath entries resolve below
`<dataset-root>/<project_id>/`; absolute entries are preserved.

For the audited LLM benchmark use the canonical 273-class file:

```text
/scratch/fraser/llm-benchmark/selected_classes_evosuite.csv
SHA-256 1baebf4177ab280b7b8efb600f93a2d1d848f9f5f3fa484f2898a9c0eca16d60
```

Do not replace it with the raw Maven classpath export: test-scope Mockito artifacts conflict with
EvoSuite's instrumenting class loader, and the audited file also contains symmetric exclusions for
environmentally infeasible or arm-independent failures.

## Local inspection

From `experiments/objectconstruction/scripts`:

```bash
python3 run_experiment.py --config-group final --list-configs

python3 run_experiment.py \
  --dataset-root /Users/gordon/Documents/Source/llm-test/benchmark \
  --corpus /Users/gordon/Documents/Source/llm-test/benchmark/selected_classes_evosuite.csv \
  --config-group final --limit-classes 1 --reps 1
```

To produce manifests locally, add `--generate-manifest /tmp/evoobj-smoke`.

## Slurm submission

Run from `experiments/objectconstruction/scripts`. First submit a one-repetition post-cleanup
smoke run:

```bash
export RUN_DIR=/scratch/fraser/evosuite/runs/objconstruction_smoke_$(date +%Y%m%d_%H%M%S)
DATASET_ROOT=/scratch/fraser/llm-benchmark \
CORPUS=/scratch/fraser/llm-benchmark/selected_classes_evosuite.csv \
EVOSUITE_JAR=/scratch/fraser/evosuite/evoobj.jar \
CONFIG_GROUP=final \
REPS=1 SEARCH_BUDGET=120 SEED=42 \
CHUNK_SIZE=15 ARRAY_THROTTLE=8 \
./submit_slurm.sh
```

After Phase H passes, use the same command with `REPS=10` and a definitive `RUN_DIR`.
`submit_slurm.sh` snapshots the corpus and validator and records their hashes plus the jar hash
under `RUN_DIR/provenance/`.

Important environment variables:

- `CONFIG_GROUP` defaults to `final`; `CONFIGS` selects explicit IDs.
- `REPS` defaults to 10; run seed is `SEED + repetition`.
- `SEARCH_BUDGET` defaults to 120 seconds.
- `CHUNK_SIZE` defaults to 5 and `ARRAY_THROTTLE` to 8.
- `TIMEOUT_LIMIT` bounds Java; `OUTER_TIMEOUT_LIMIT` bounds the complete container process.
- `WRITE_TESTS=0` disables JUnit output and all test post-processing.

Each array task receives a node-local `/tmp`, preventing shared-container exhaustion. Outputs are:

```text
manifests/all_runs.tsv
manifests/chunks/chunk_*.tsv
reports/<arm>/rep<N>/<class>/statistics.csv
logs/
status/task_<id>.tsv
provenance/
slurm/
```

## Validation and metrics

Require all planned rows to terminate and emit pairwise exclusions:

```bash
python3 aggregate_results.py "$RUN_DIR" --require-finished \
  --pairwise-exclusions "$RUN_DIR/pairwise_exclusions.csv" \
  --out "$RUN_DIR/summary.csv"
```

Use `--require-complete` when every planned row must contain valid numeric statistics.

Coverage comparisons use `Coverage`, `BranchCoverage`, `LineCoverage`, and
`MethodNoExceptionCoverage`. Core EvoObj funnel metrics include graph branches and size,
eligible/blacklisted branches, fresh syntheses, template injections and cache hits, first-shot
success, immediate coverage, reached-not-covered, synthesis/execution time, inconsistent
templates, and functional-mock opportunities, attempts, successes, proactive uses, extra
executions, negative-cache hits, and blacklisted recipes.

A process failure is never converted into zero coverage. Missing, timed-out, crashed, malformed,
or invalid-statistics rows are excluded symmetrically from every arm in that
class/repetition pair. A zero exit status without valid `statistics.csv` remains invalid. Keep the
status/provenance files so environmental failures can be distinguished from EvoSuite failures.
