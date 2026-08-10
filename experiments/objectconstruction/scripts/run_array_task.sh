#!/usr/bin/env bash
#
# SLURM array task for the EvoObj object-construction experiment. Each task reads
# one chunk TSV and runs every row (a class x arm x repetition) through
# evosuite.jar inside the Apptainer image. Driven by submit_slurm.sh.
set -euo pipefail

# Required env vars (exported by submit_slurm.sh).
: "${RUN_DIR:?RUN_DIR is required}"
: "${APPTAINER_IMAGE:?APPTAINER_IMAGE is required}"
: "${EVOSUITE_JAR:?EVOSUITE_JAR is required}"
: "${DATASET_ROOT:?DATASET_ROOT is required}"
: "${STATISTICS_VALIDATOR:?STATISTICS_VALIDATOR is required}"

if [[ ! -f "${STATISTICS_VALIDATOR}" ]]; then
  echo "Missing statistics validator: ${STATISTICS_VALIDATOR}" >&2
  exit 2
fi

TASK_ID="${SLURM_ARRAY_TASK_ID:-0}"
MANIFEST_FILE="${RUN_DIR}/manifests/chunks/chunk_$(printf '%05d' "${TASK_ID}").tsv"

RETRY_COUNT="${RETRY_COUNT:-1}"
TIMEOUT_LIMIT="${TIMEOUT_LIMIT:-30m}"
# Independent host-side bound around Apptainer. The inner timeout bounds Java, while this one
# also terminates a wedged container runtime and every descendant process. Keep it longer than the
# inner bound so EvoSuite normally has time to write its terminal statistics row.
OUTER_TIMEOUT_LIMIT="${OUTER_TIMEOUT_LIMIT:-35m}"
OUTER_TIMEOUT_KILL_AFTER="${OUTER_TIMEOUT_KILL_AFTER:-30s}"
EXTRA_TIMEOUT="${EXTRA_TIMEOUT:-600}"
JUNIT_CHECK_TIMEOUT="${JUNIT_CHECK_TIMEOUT:-1200}"
EVOSUITE_MEM_MB="${EVOSUITE_MEM_MB:-4000}"
NOFILE_SOFT_LIMIT="${NOFILE_SOFT_LIMIT:-8192}"

# Keep HOME on /scratch so EvoSuite's dotfiles don't hit a read-only home.
export HOME="/scratch/${USER}"
mkdir -p "${HOME}"

# Prefer node-local storage for temporary files.
LOCAL_TMP_BASE="${LOCAL_TMP_BASE:-${SLURM_TMPDIR:-/local/${USER}}}"
TASK_TMP_DIR="${LOCAL_TMP_BASE}/evosuite_objconstruction_${SLURM_JOB_ID:-nojob}_${TASK_ID}"
if mkdir -p "${TASK_TMP_DIR}" 2>/dev/null; then
  export TMPDIR="${TASK_TMP_DIR}"
else
  echo "Warning: unable to create isolated task tmp under ${LOCAL_TMP_BASE}" >&2
  TASK_TMP_DIR="${RUN_DIR}/tmp/evosuite_objconstruction_${SLURM_JOB_ID:-nojob}_${TASK_ID}"
  mkdir -p "${TASK_TMP_DIR}"
  export TMPDIR="${TASK_TMP_DIR}"
fi
export APPTAINER_TMPDIR="${TASK_TMP_DIR}"
cleanup_task_tmp() {
  case "${TASK_TMP_DIR}" in
    */evosuite_objconstruction_*)
      rm -rf -- "${TASK_TMP_DIR}"
      ;;
  esac
}
trap cleanup_task_tmp EXIT

if [[ "${NOFILE_SOFT_LIMIT}" =~ ^[0-9]+$ ]]; then
  hard_nofile="$(ulimit -Hn)"
  desired_nofile="${NOFILE_SOFT_LIMIT}"
  if [[ "${hard_nofile}" =~ ^[0-9]+$ ]] && (( desired_nofile > hard_nofile )); then
    desired_nofile="${hard_nofile}"
  fi
  if ! ulimit -Sn "${desired_nofile}" 2>/dev/null; then
    echo "Warning: unable to set soft nofile limit to ${desired_nofile}" >&2
  fi
fi

mkdir -p \
  "${RUN_DIR}/logs" \
  "${RUN_DIR}/reports" \
  "${RUN_DIR}/status"

if [[ ! -f "${MANIFEST_FILE}" ]]; then
  echo "Missing manifest file for task ${TASK_ID}: ${MANIFEST_FILE}" >&2
  exit 2
fi

EVOSUITE_BIND_DIR="$(dirname "${EVOSUITE_JAR}")"
EVOSUITE_JAR_BASENAME="$(basename "${EVOSUITE_JAR}")"
STATUS_FILE="${RUN_DIR}/status/task_${TASK_ID}.tsv"

if [[ ! -f "${STATUS_FILE}" ]]; then
  printf 'task_id\trun_id\tproject_id\tclass_name\tconfig_id\trepetition\texit_code\tattempts\tfailure_reason\n' > "${STATUS_FILE}"
fi

# Manifest format: run_id, project_id, class_name, classpath, config_id, repetition, extra_args
while IFS=$'\t' read -r run_id project_id class_name classpath config_id repetition extra_args; do
  if [[ "${run_id}" == "run_id" ]]; then
    continue
  fi

  run_id=$(echo "${run_id}" | tr -d '\r')
  project_id=$(echo "${project_id}" | tr -d '\r')
  class_name=$(echo "${class_name}" | tr -d '\r')
  classpath=$(echo "${classpath}" | tr -d '\r')
  config_id=$(echo "${config_id}" | tr -d '\r')
  repetition=$(echo "${repetition}" | tr -d '\r')
  extra_args=$(echo "${extra_args}" | tr -d '\r')

  # The selected host dataset is bound to /dataset inside the container; rewrite absolute
  # classpath entries emitted by the manifest generator to their container-visible paths.
  classpath=$(echo "${classpath}" | sed "s|${DATASET_ROOT}|/dataset|g")

  project_dir="/dataset/${project_id}"
  logfile="${RUN_DIR}/logs/${project_id}_${class_name}_${config_id}_R${repetition}.log"
  class_segment=$(printf '%s' "${class_name}" | sed 's/[^A-Za-z0-9_.$-]/_/g')
  statistics_file="${RUN_DIR}/reports/${config_id}/rep${repetition}/${class_segment}/statistics.csv"

  mkdir -p "${RUN_DIR}/reports/${config_id}/rep${repetition}"

  attempt=0
  exit_code=1
  failure_reason="not_started"
  while (( attempt <= RETRY_COUNT )); do
    attempt=$((attempt + 1))
    attempt_log="${logfile}.attempt${attempt}.tmp"
    rm -f "${attempt_log}"
    # Never allow a successful row from an earlier attempt to validate a later
    # attempt that failed before writing statistics.
    rm -f "${statistics_file}"

    set +e
    timeout --signal=TERM --kill-after="${OUTER_TIMEOUT_KILL_AFTER}" \
      "${OUTER_TIMEOUT_LIMIT}" \
      apptainer exec \
      --bind "${DATASET_ROOT}:/dataset" \
      --bind "${EVOSUITE_BIND_DIR}:/evosuite" \
      --bind "${RUN_DIR}:/results" \
      --bind "${TASK_TMP_DIR}:/tmp" \
      "${APPTAINER_IMAGE}" \
      bash -s -- \
        "${project_dir}" \
        "${EVOSUITE_JAR_BASENAME}" \
        "${class_name}" \
        "${classpath}" \
        "${project_id}" \
        "${TIMEOUT_LIMIT}" \
        "${EVOSUITE_MEM_MB}" \
        "${EXTRA_TIMEOUT}" \
        "${JUNIT_CHECK_TIMEOUT}" \
        "${extra_args}" \
        "${config_id}" \
        "${repetition}" \
      > "${attempt_log}" 2>&1 <<'EOF'
set -euo pipefail
project_dir="$1"
jar_name="$2"
target_class="$3"
project_cp="$4"
group_id="$5"
timeout_limit="$6"
mem_mb="$7"
extra_timeout="$8"
junit_check_timeout="$9"
extra_args_str="${10}"
config_id="${11}"
repetition="${12}"

cd "${project_dir}"

cmd=(
  java
  "-Xmx${mem_mb}m"
  -jar "/evosuite/${jar_name}"
)

# Expand the manifest's extra_args string into the command.
eval "cmd+=($extra_args_str)"

cmd+=(
  -projectCP "${project_cp}"
  -class "${target_class}"
  -Dgroup_id="${group_id}"
  -Dconfiguration_id="${config_id}"
  -mem "${mem_mb}"
  -Dextra_timeout="${extra_timeout}"
  -Djunit_check_timeout="${junit_check_timeout}"
)

printf 'EvoSuite command: ' >&2
printf '%q ' "${cmd[@]}" >&2
printf '\n' >&2

timeout -k "${timeout_limit}" "${timeout_limit}" "${cmd[@]}"
EOF
    exit_code=$?
    set -e
    cat "${attempt_log}" >> "${logfile}"
    failure_reason="process_exit_${exit_code}"
    if [[ ${exit_code} -eq 124 ]]; then
      failure_reason="outer_timeout"
    fi

    # EvoSuite's master process can exit 0 after a forked client was SIGKILLed
    # and only report the real client status in the log. Treat that as a failed
    # run so status TSVs and retries reflect the actual client outcome.
    if [[ ${exit_code} -eq 0 ]]; then
      hidden_client_exit="$(
        sed -nE 's/.*terminated unexpectedly\..*exitCode=([0-9]+).*/\1/p' "${attempt_log}" | tail -n 1
      )"
      if [[ -n "${hidden_client_exit}" ]]; then
        exit_code="${hidden_client_exit}"
        failure_reason="client_exit_${hidden_client_exit}"
      fi
    fi

    # Process exit zero is necessary but insufficient.  EvoSuite can write a
    # partial N/A row after ERROR/TIMEOUT and still let the master exit zero.
    if [[ ${exit_code} -eq 0 ]]; then
      set +e
      validation_reason="$(python3 "${STATISTICS_VALIDATOR}" "${statistics_file}" 2>&1)"
      validation_exit=$?
      set -e
      if [[ ${validation_exit} -ne 0 ]]; then
        failure_reason="$(printf '%s' "${validation_reason}" | tr '\t\r\n' '___')"
        exit_code=65
        echo "run_id=${run_id} statistics validation failed: ${failure_reason}" >> "${logfile}"
      else
        failure_reason="success"
      fi
    fi
    rm -f "${attempt_log}"

    if [[ ${exit_code} -eq 0 ]]; then
      break
    fi

    echo "run_id=${run_id} failed with code ${exit_code} (attempt ${attempt}/$((RETRY_COUNT + 1)))" >> "${logfile}"
  done

  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
    "${TASK_ID}" "${run_id}" "${project_id}" "${class_name}" "${config_id}" "${repetition}" "${exit_code}" "${attempt}" "${failure_reason}" \
    >> "${STATUS_FILE}"

done < "${MANIFEST_FILE}"
