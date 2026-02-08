#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LTS_VERSIONS=(8 11 17 21 25)

command_exists() {
  command -v "$1" >/dev/null 2>&1
}

find_java_home() {
  local lts="$1"
  local home=""

  if command_exists /usr/libexec/java_home; then
    home=$(/usr/libexec/java_home -v "$lts" 2>/dev/null || true)
  fi

  if [[ -z "$home" ]] && command_exists jenv; then
    local root
    root=$(jenv root 2>/dev/null || true)
    if [[ -n "$root" && -d "$root/versions" ]]; then
      home=$(ls -d "$root/versions/$lts"* 2>/dev/null | head -n 1 || true)
    fi
  fi

  if [[ -z "$home" ]] && command_exists asdf; then
    home=$(asdf where java "temurin-$lts" 2>/dev/null || true)
    if [[ -z "$home" ]]; then
      home=$(asdf where java "openjdk-$lts" 2>/dev/null || true)
    fi
  fi

  if [[ -z "$home" ]] && [[ -n "${SDKMAN_CANDIDATES_DIR:-}" ]]; then
    home=$(ls -d "$SDKMAN_CANDIDATES_DIR/java/"*"$lts"* 2>/dev/null | head -n 1 || true)
  fi

  if [[ -z "$home" ]] && [[ -d "/usr/lib/jvm" ]]; then
    home=$(ls -d /usr/lib/jvm/*"$lts"* 2>/dev/null | head -n 1 || true)
  fi

  if [[ -z "$home" ]] && [[ -d "/Library/Java/JavaVirtualMachines" ]]; then
    local jdk
    jdk=$(ls -d /Library/Java/JavaVirtualMachines/*"$lts"* 2>/dev/null | head -n 1 || true)
    if [[ -n "$jdk" && -d "$jdk/Contents/Home" ]]; then
      home="$jdk/Contents/Home"
    fi
  fi

  if [[ -n "$home" && -x "$home/bin/java" ]]; then
    echo "$home"
  fi
}

build_jar() {
  echo "Building EvoSuite (client jar) ..."
  if ! (cd "$ROOT_DIR" && mvn -pl client -am package -DskipTests); then
    echo "ERROR: Maven build failed. See output above."
    exit 1
  fi
}

find_jar() {
  local jar=""
  local pattern_client="$ROOT_DIR/client/target/evosuite-client-*.jar"
  local candidate
  for candidate in $(compgen -G "$pattern_client"); do
    case "$candidate" in
      *-tests.jar) continue ;;
    esac
    jar="$candidate"
    break
  done
  if [[ -n "$jar" ]]; then
    echo "$jar"
    return 0
  fi

  return 1
}

build_client_classpath() {
  local cp_file="$ROOT_DIR/client/target/runtime-classpath.txt"
  if ! (cd "$ROOT_DIR" && mvn -pl client -am -DskipTests -DincludeScope=runtime -Dmdep.outputFile="$cp_file" dependency:build-classpath >/dev/null); then
    echo "ERROR: Failed to build client runtime classpath."
    exit 1
  fi
  if [[ ! -s "$cp_file" ]]; then
    echo "ERROR: Runtime classpath file is empty: $cp_file"
    exit 1
  fi
  echo "$cp_file"
}

filter_classpath() {
  local cp="$1"
  local filtered=""
  IFS=':' read -r -a parts <<< "$cp"
  for p in "${parts[@]}"; do
    if [[ "$p" == *logback-classic* || "$p" == *logback-core* ]]; then
      continue
    fi
    if [[ -z "$filtered" ]]; then
      filtered="$p"
    else
      filtered="$filtered:$p"
    fi
  done
  echo "$filtered"
}

usage() {
  cat <<'USAGE'
Usage: generate_jdk_inheritance_trees.sh [--list] [--dry-run]

Options:
  --list     List detected JDK homes for target LTS versions and exit.
  --dry-run  Show what would run without executing java.
USAGE
}

MODE_RUN=1
MODE_LIST=0
MODE_DRY_RUN=0

for arg in "$@"; do
  case "$arg" in
    --list)
      MODE_LIST=1
      MODE_RUN=0
      ;;
    --dry-run)
      MODE_DRY_RUN=1
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $arg"
      usage
      exit 1
      ;;
  esac
done

JAR_PATH=""
CLIENT_CP_FILE=""
CLIENT_CP=""
if [[ "$MODE_LIST" -eq 0 ]]; then
  JAR_PATH=$(find_jar || true)
  if [[ -z "$JAR_PATH" ]]; then
    build_jar
    JAR_PATH=$(find_jar)
  fi

  if [[ -z "$JAR_PATH" ]]; then
    echo "ERROR: Could not locate client/target/evosuite-client-*.jar after build."
    echo "Contents of client/target:"
    ls -la "$ROOT_DIR/client/target" || true
    exit 1
  fi

  echo "Using jar: $JAR_PATH"
  mkdir -p "$ROOT_DIR/client/src/main/resources"

  CLIENT_CP_FILE=$(build_client_classpath)
  CLIENT_CP=$(cat "$CLIENT_CP_FILE")
  CLIENT_CP=$(filter_classpath "$CLIENT_CP")
fi

generated=0
skipped=0

for lts in "${LTS_VERSIONS[@]}"; do
  var_name="JAVA_HOME_${lts}"
  java_home=$(eval "echo \${$var_name:-}")
  if [[ -z "$java_home" ]]; then
    java_home=$(find_java_home "$lts" || true)
  fi
  if [[ -z "$java_home" ]]; then
    if [[ "$MODE_LIST" -eq 1 ]]; then
      echo "JDK $lts: not found"
      skipped=$((skipped + 1))
      continue
    fi
    echo "Skipping JDK $lts (set $var_name or install via jenv/asdf/sdkman/system)"
    skipped=$((skipped + 1))
    continue
  fi
  java_bin="$java_home/bin/java"
  if [[ ! -x "$java_bin" ]]; then
    if [[ "$MODE_LIST" -eq 1 ]]; then
      echo "JDK $lts: $java_home (java not executable)"
      skipped=$((skipped + 1))
      continue
    fi
    echo "Skipping JDK $lts ($java_bin not executable)"
    skipped=$((skipped + 1))
    continue
  fi

  if [[ "$MODE_LIST" -eq 1 ]]; then
    echo "JDK $lts: $java_home"
    generated=$((generated + 1))
    continue
  fi

  if [[ "$MODE_DRY_RUN" -eq 1 ]]; then
    echo "DRY RUN: JDK $lts ($java_bin) -> org.evosuite.setup.InheritanceTreeGenerator"
    generated=$((generated + 1))
    continue
  fi

  if [[ "$MODE_RUN" -eq 1 ]]; then
    echo "Generating inheritance tree for JDK $lts ($java_bin)"
    add_opens=()
    if [[ "$lts" -ge 17 ]]; then
      add_opens+=(--add-opens java.base/java.util=ALL-UNNAMED)
      add_opens+=(--add-opens java.base/java.lang=ALL-UNNAMED)
      add_opens+=(--add-opens java.base/java.io=ALL-UNNAMED)
    fi
    (cd "$ROOT_DIR" && JAVA_HOME="$java_home" "$java_bin" "${add_opens[@]}" -cp "$JAR_PATH:$CLIENT_CP" org.evosuite.setup.InheritanceTreeGenerator)
    expected_xml="$ROOT_DIR/client/src/main/resources/JDK_inheritance_${lts}.xml"
    expected_shaded="$ROOT_DIR/client/src/main/resources/JDK_inheritance_${lts}_shaded.xml"
    if [[ ! -f "$expected_xml" || ! -f "$expected_shaded" ]]; then
      echo "WARNING: Expected outputs not found for JDK $lts:"
      echo "  missing: $expected_xml"
      echo "  missing: $expected_shaded"
    fi
    generated=$((generated + 1))
  fi

done

if [[ "$MODE_LIST" -eq 1 ]]; then
  echo "Detected: $generated, Missing: $skipped"
  exit 0
fi

if [[ "$MODE_DRY_RUN" -eq 1 ]]; then
  echo "Dry run complete. Planned: $generated, Skipped: $skipped"
  exit 0
fi

if [[ "$generated" -eq 0 ]]; then
  echo "ERROR: No JDKs were found or used. Run with --list and set JAVA_HOME_<LTS> variables."
  exit 1
fi

echo "Done. Generated: $generated, Skipped: $skipped"

echo "Expected outputs in: $ROOT_DIR/client/src/main/resources"
