#!/usr/bin/env bash
#
# Generate the EvoObj object-construction manifest and submit it as a SLURM
# array. Mirrors the LLM experiment's submit_phaseN_slurm.sh: one array task per
# chunk of runs, each run executed inside the Apptainer image against evosuite.jar.
#
# Every knob is overridable via the environment, e.g.:
#   REPS=10 SEARCH_BUDGET=120 CONFIG_GROUP=final ./submit_slurm.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

APPTAINER_IMAGE="${APPTAINER_IMAGE:-/scratch/fraser/evosuite/images/temurin17.sif}"
EVOSUITE_JAR="${EVOSUITE_JAR:-/scratch/fraser/evosuite/evosuite.jar}"
DATASET_ROOT="${DATASET_ROOT:-/scratch/fraser/sf110-with-dependencies}"
RUN_DIR="${RUN_DIR:-/scratch/${USER}/evosuite/runs/objconstruction_$(date +%Y%m%d_%H%M%S)}"

CORPUS="${CORPUS:-${SCRIPT_DIR}/../data/classes.csv}"
LIMIT_CLASSES="${LIMIT_CLASSES:-}"
REPS="${REPS:-10}"
CHUNK_SIZE="${CHUNK_SIZE:-5}"
ARRAY_THROTTLE="${ARRAY_THROTTLE:-8}"
SEARCH_BUDGET="${SEARCH_BUDGET:-120}"
SEED="${SEED:-42}"
CONFIG_GROUP="${CONFIG_GROUP:-final}"
CONFIGS="${CONFIGS:-}"

# WRITE_TESTS=1 bakes -Djunit_tests/minimize/assertions/test_comments=true into the manifest so
# readable JUnit suites (with assertions) are written to each run's tests/ dir. Off by default;
# adds minimization + JUnit-generation time and disk, so pair it with LIMIT_CLASSES for a subset.
WRITE_TESTS="${WRITE_TESTS:-0}"
export WRITE_TESTS

SLURM_CPUS_PER_TASK="${SLURM_CPUS_PER_TASK:-1}"
SLURM_MEM="${SLURM_MEM:-6G}"
SLURM_TIME="${SLURM_TIME:-24:00:00}"
SLURM_ACCOUNT="${SLURM_ACCOUNT:-chair-se2}"
SLURM_PARTITION="${SLURM_PARTITION:-general}"
SLURM_CONSTRAINT="${SLURM_CONSTRAINT:-}"

# Runner-side controls (consumed by run_array_task.sh).
TIMEOUT_LIMIT="${TIMEOUT_LIMIT:-30m}"
OUTER_TIMEOUT_LIMIT="${OUTER_TIMEOUT_LIMIT:-35m}"
OUTER_TIMEOUT_KILL_AFTER="${OUTER_TIMEOUT_KILL_AFTER:-30s}"
EXTRA_TIMEOUT="${EXTRA_TIMEOUT:-600}"
JUNIT_CHECK_TIMEOUT="${JUNIT_CHECK_TIMEOUT:-1200}"
EVOSUITE_MEM_MB="${EVOSUITE_MEM_MB:-4000}"
NOFILE_SOFT_LIMIT="${NOFILE_SOFT_LIMIT:-8192}"
RETRY_COUNT="${RETRY_COUNT:-1}"

PROVENANCE_DIR="${RUN_DIR}/provenance"
mkdir -p "${RUN_DIR}" "${RUN_DIR}/slurm" "${PROVENANCE_DIR}"

# Freeze the exact corpus used to build the manifest. This also prevents a CSV
# edit during submission from producing an irreproducible run directory.
CORPUS_SNAPSHOT="${PROVENANCE_DIR}/corpus.csv"
STATISTICS_VALIDATOR="${PROVENANCE_DIR}/validate_statistics.py"
cp "${CORPUS}" "${CORPUS_SNAPSHOT}"
# SLURM executes a spool copy of run_array_task.sh, so BASH_SOURCE points at the
# spool directory rather than this source directory. Snapshot the validator in
# the shared run directory and pass that absolute path to every array task.
cp "${SCRIPT_DIR}/validate_statistics.py" "${STATISTICS_VALIDATOR}"
if command -v sha256sum >/dev/null 2>&1; then
  sha256sum "${CORPUS_SNAPSHOT}" > "${PROVENANCE_DIR}/corpus.sha256"
  sha256sum "${EVOSUITE_JAR}" > "${PROVENANCE_DIR}/evosuite_jar.sha256"
  sha256sum "${STATISTICS_VALIDATOR}" > "${PROVENANCE_DIR}/statistics_validator.sha256"
else
  shasum -a 256 "${CORPUS_SNAPSHOT}" > "${PROVENANCE_DIR}/corpus.sha256"
  shasum -a 256 "${EVOSUITE_JAR}" > "${PROVENANCE_DIR}/evosuite_jar.sha256"
  shasum -a 256 "${STATISTICS_VALIDATOR}" > "${PROVENANCE_DIR}/statistics_validator.sha256"
fi
{
  printf 'DATASET_ROOT=%s\n' "${DATASET_ROOT}"
  printf 'CONFIG_GROUP=%s\n' "${CONFIG_GROUP}"
  printf 'CONFIGS=%s\n' "${CONFIGS}"
  printf 'REPS=%s\n' "${REPS}"
  printf 'SEARCH_BUDGET=%s\n' "${SEARCH_BUDGET}"
  printf 'SEED=%s\n' "${SEED}"
  printf 'CHUNK_SIZE=%s\n' "${CHUNK_SIZE}"
  printf 'TIMEOUT_LIMIT=%s\n' "${TIMEOUT_LIMIT}"
  printf 'OUTER_TIMEOUT_LIMIT=%s\n' "${OUTER_TIMEOUT_LIMIT}"
  printf 'OUTER_TIMEOUT_KILL_AFTER=%s\n' "${OUTER_TIMEOUT_KILL_AFTER}"
  printf 'EVOSUITE_JAR=%s\n' "${EVOSUITE_JAR}"
} > "${PROVENANCE_DIR}/settings.env"

echo "Generating manifest for the object-construction experiment..."
RUN_ARGS=(
  --corpus "${CORPUS_SNAPSHOT}"
  --dataset-root "${DATASET_ROOT}"
  --reps "${REPS}"
  --chunk-size "${CHUNK_SIZE}"
  --search-budget "${SEARCH_BUDGET}"
  --seed "${SEED}"
  --config-group "${CONFIG_GROUP}"
  --generate-manifest "${RUN_DIR}"
)

if [[ -n "${LIMIT_CLASSES}" ]]; then
  RUN_ARGS+=(--limit-classes "${LIMIT_CLASSES}")
fi

if [[ -n "${CONFIGS}" ]]; then
  IFS=',' read -r -a CONFIG_ARRAY <<< "${CONFIGS}"
  for CONFIG_ID in "${CONFIG_ARRAY[@]}"; do
    RUN_ARGS+=(--config "${CONFIG_ID}")
  done
fi

python3 "${SCRIPT_DIR}/run_experiment.py" "${RUN_ARGS[@]}"

CHUNK_COUNT=$(find "${RUN_DIR}/manifests/chunks" -name 'chunk_*.tsv' | wc -l | tr -d ' ')
if [[ "${CHUNK_COUNT}" -eq 0 ]]; then
  echo "No chunk files found" >&2
  exit 2
fi

ARRAY_SPEC="0-$((CHUNK_COUNT - 1))%${ARRAY_THROTTLE}"

SBATCH_ARGS=(
  --job-name=evoobj
  --array="${ARRAY_SPEC}"
  --cpus-per-task="${SLURM_CPUS_PER_TASK}"
  --mem="${SLURM_MEM}"
  --time="${SLURM_TIME}"
  --account="${SLURM_ACCOUNT}"
  --partition="${SLURM_PARTITION}"
  --output="${RUN_DIR}/slurm/slurm_%A_%a.out"
  --error="${RUN_DIR}/slurm/slurm_%A_%a.err"
  --export=ALL,RUN_DIR="${RUN_DIR}",APPTAINER_IMAGE="${APPTAINER_IMAGE}",EVOSUITE_JAR="${EVOSUITE_JAR}",DATASET_ROOT="${DATASET_ROOT}",STATISTICS_VALIDATOR="${STATISTICS_VALIDATOR}",TIMEOUT_LIMIT="${TIMEOUT_LIMIT}",OUTER_TIMEOUT_LIMIT="${OUTER_TIMEOUT_LIMIT}",OUTER_TIMEOUT_KILL_AFTER="${OUTER_TIMEOUT_KILL_AFTER}",EXTRA_TIMEOUT="${EXTRA_TIMEOUT}",JUNIT_CHECK_TIMEOUT="${JUNIT_CHECK_TIMEOUT}",EVOSUITE_MEM_MB="${EVOSUITE_MEM_MB}",NOFILE_SOFT_LIMIT="${NOFILE_SOFT_LIMIT}",RETRY_COUNT="${RETRY_COUNT}"
)

if [[ -n "${SLURM_CONSTRAINT}" ]]; then
  SBATCH_ARGS+=(--constraint="${SLURM_CONSTRAINT}")
fi

echo "Submitting array with ${CHUNK_COUNT} tasks (spec: ${ARRAY_SPEC})"
echo "Run dir: ${RUN_DIR}"

sbatch "${SBATCH_ARGS[@]}" "${SCRIPT_DIR}/run_array_task.sh"
