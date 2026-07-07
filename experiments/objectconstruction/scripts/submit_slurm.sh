#!/usr/bin/env bash
#
# Generate the EvoObj object-construction manifest and submit it as a SLURM
# array. Mirrors the LLM experiment's submit_phaseN_slurm.sh: one array task per
# chunk of runs, each run executed inside the Apptainer image against evosuite.jar.
#
# Every knob is overridable via the environment, e.g.:
#   REPS=20 SEARCH_BUDGET=180 CONFIG_GROUP=core ./submit_slurm.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

APPTAINER_IMAGE="${APPTAINER_IMAGE:-/scratch/fraser/evosuite/images/temurin17.sif}"
EVOSUITE_JAR="${EVOSUITE_JAR:-/scratch/fraser/evosuite/evosuite.jar}"
DATASET_ROOT="${DATASET_ROOT:-/scratch/fraser/sf110-with-dependencies}"
export SF110_ROOT="${DATASET_ROOT}"
RUN_DIR="${RUN_DIR:-/scratch/${USER}/evosuite/runs/objconstruction_$(date +%Y%m%d_%H%M%S)}"

CORPUS="${CORPUS:-${SCRIPT_DIR}/../data/classes.csv}"
LIMIT_CLASSES="${LIMIT_CLASSES:-}"
REPS="${REPS:-10}"
CHUNK_SIZE="${CHUNK_SIZE:-5}"
ARRAY_THROTTLE="${ARRAY_THROTTLE:-8}"
SEARCH_BUDGET="${SEARCH_BUDGET:-120}"
SEED="${SEED:-42}"
CONFIG_GROUP="${CONFIG_GROUP:-full}"
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
EXTRA_TIMEOUT="${EXTRA_TIMEOUT:-600}"
JUNIT_CHECK_TIMEOUT="${JUNIT_CHECK_TIMEOUT:-1200}"
EVOSUITE_MEM_MB="${EVOSUITE_MEM_MB:-4000}"
NOFILE_SOFT_LIMIT="${NOFILE_SOFT_LIMIT:-8192}"
RETRY_COUNT="${RETRY_COUNT:-1}"

mkdir -p "${RUN_DIR}" "${RUN_DIR}/slurm"

echo "Generating manifest for the object-construction experiment..."
RUN_ARGS=(
  --corpus "${CORPUS}"
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
  --export=ALL,RUN_DIR="${RUN_DIR}",APPTAINER_IMAGE="${APPTAINER_IMAGE}",EVOSUITE_JAR="${EVOSUITE_JAR}",DATASET_ROOT="${DATASET_ROOT}",SF110_ROOT="${SF110_ROOT}",TIMEOUT_LIMIT="${TIMEOUT_LIMIT}",EXTRA_TIMEOUT="${EXTRA_TIMEOUT}",JUNIT_CHECK_TIMEOUT="${JUNIT_CHECK_TIMEOUT}",EVOSUITE_MEM_MB="${EVOSUITE_MEM_MB}",NOFILE_SOFT_LIMIT="${NOFILE_SOFT_LIMIT}",RETRY_COUNT="${RETRY_COUNT}"
)

if [[ -n "${SLURM_CONSTRAINT}" ]]; then
  SBATCH_ARGS+=(--constraint="${SLURM_CONSTRAINT}")
fi

echo "Submitting array with ${CHUNK_COUNT} tasks (spec: ${ARRAY_SPEC})"
echo "Run dir: ${RUN_DIR}"

sbatch "${SBATCH_ARGS[@]}" "${SCRIPT_DIR}/run_array_task.sh"
