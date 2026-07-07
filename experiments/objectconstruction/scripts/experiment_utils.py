"""Shared helpers for the EvoObj object-construction experiment scripts.

Deliberately self-contained (no LLM-experiment dependencies): the manifest
generator and any analysis script import only from here so the whole toolchain
can be copied alongside the repo.
"""

import csv
import os
import re

_CLASS_SEGMENT_RE = re.compile(r"[^A-Za-z0-9_.$-]")


def sanitize_class_segment(class_name):
    """Filesystem-safe segment derived from a fully-qualified Java class name.

    Used as the per-class subdirectory of ``report_dir`` so the sidecar CSVs
    EvoSuite writes in overwrite mode do not collide across classes that share
    the same config/rep folder.
    """
    if not class_name:
        return "_unknown"
    return _CLASS_SEGMENT_RE.sub("_", class_name)


def load_classes(data_file, limit=None, start=0):
    """Read the corpus CSV.

    Columns: project_id, class_name, java_path, complexity, classpath. Only
    project_id, class_name and classpath are consumed; any extra columns
    (java_path, complexity, ...) are ignored. Returns a list of dict rows, or
    ``None`` if the file does not exist.
    """
    if not os.path.exists(data_file):
        return None
    classes = []
    with open(data_file, "r", newline="") as f:
        reader = csv.DictReader(f)
        for i, row in enumerate(reader):
            if i < start:
                continue
            if limit is not None and i >= start + limit:
                break
            classes.append(row)
    return classes


def java_d_key(arg):
    """Return the property name for a ``-Dkey=value`` argument, or ``None``."""
    if not isinstance(arg, str) or not arg.startswith("-D"):
        return None
    assignment = arg[2:]
    if "=" not in assignment:
        return None
    key = assignment.split("=", 1)[0].strip()
    return key or None


def merge_evosuite_args(*arg_groups):
    """Merge argument blocks with explicit Java ``-D`` last-wins semantics.

    Later blocks override earlier ones: an earlier occurrence of the same Java
    property is removed so the manifest carries one unambiguous value per key.
    Non ``-D`` tokens are appended verbatim in order.
    """
    merged = []
    positions = {}
    for group in arg_groups:
        if not group:
            continue
        for arg in group:
            key = java_d_key(arg)
            if key is not None and key in positions:
                old_position = positions.pop(key)
                merged.pop(old_position)
                positions = {
                    existing_key: position - 1 if position > old_position else position
                    for existing_key, position in positions.items()
                }
            merged.append(arg)
            if key is not None:
                positions[key] = len(merged) - 1
    return merged


def java_d_options(args):
    """Return the effective Java property map and reject duplicate keys."""
    options = {}
    for arg in args:
        key = java_d_key(arg)
        if key is None:
            continue
        if key in options:
            raise ValueError(f"Duplicate Java property in final argument list: {key}")
        options[key] = arg.split("=", 1)[1]
    return options


# Coverage / bookkeeping columns plus the EvoObj-specific statistics, kept in one
# schema across every arm. The baseline arm simply reports empty/zero for the
# object-rule metrics, which lets an aggregator tell a genuine zero from a
# missing datapoint.
CORE_OUTPUT_VARIABLES = (
    "TARGET_CLASS",
    "criterion",
    "configuration_id",
    "group_id",
    "algorithm",
    "search_budget",
    "Random_Seed",
    "Total_Goals",
    "Covered_Goals",
    "Coverage",
    "BranchCoverage",
    "LineCoverage",
    "MethodCoverage",
    "MethodNoExceptionCoverage",
    "Size",
    "Length",
    "Total_Time",
    "Generations",
    "Statements_Executed",
    "Tests_Executed",
    "HadUnstableTests",
    "NumUnstableTests",
)

# EvoObj object-construction statistics (see RuntimeVariable.java).
OBJECT_RULE_OUTPUT_VARIABLES = (
    "ObjectGraphBranches",
    "ObjectGraphMaxSize",
    "TemplateSynthesisTime",
    # Phase breakdown of TemplateSynthesisTime (synthesis vs template execution vs legitimizer).
    "ObjectRuleSynthesisTime",
    "ObjectRuleTemplateExecTime",
    "ObjectRuleLegitimizationTime",
    "TemplateInjections",
    "LegitimizationSuccessRate",
    # Raw legitimization counters so the success rate's numerator/denominator and the
    # budget-starvation signal (skipped) are visible, not just the ratio.
    "LegitimizationAttempts",
    "LegitimizationSuccesses",
    "LegitimizationSkipped",
)


def all_output_variables():
    return list(CORE_OUTPUT_VARIABLES) + list(OBJECT_RULE_OUTPUT_VARIABLES)


def get_standard_evosuite_args(search_budget):
    """Baseline EvoSuite configuration shared by every arm.

    Only the object-rule flags differ between arms; everything here is held
    constant so the comparison is apples-to-apples.
    """
    extra_timeout = os.environ.get("EXTRA_TIMEOUT", "600")
    junit_check_timeout = os.environ.get("JUNIT_CHECK_TIMEOUT", "1200")
    budget = int(search_budget)
    # WRITE_TESTS=1 emits readable, minimized JUnit suites with assertions and comments so the
    # generated tests can be inspected manually. Off by default: the cluster runs only need
    # statistics.csv, and skipping minimization/assertion/JUnit generation saves time and disk
    # across the full run matrix. Use with LIMIT_CLASSES for a small diagnostic subset.
    write_tests = os.environ.get("WRITE_TESTS", "0").strip().lower() in ("1", "true", "yes", "on")
    on_off = "true" if write_tests else "false"
    return [
        "-Dshow_progress=false",
        "-Dplot=false",
        f"-Dtest_comments={on_off}",
        f"-Dassertions={on_off}",
        f"-Dminimize={on_off}",
        f"-Djunit_tests={on_off}",
        "-Denable_asserts_for_evosuite=true",
        "-Dignore_missing_statistics=true",
        "-Dstrategy=MOSUITE",
        "-Dalgorithm=DYNAMOSA",
        "-Dstopping_condition=MaxTime",
        f"-Dsearch_budget={budget}",
        f"-Dglobal_timeout={budget}",
        "-Dinitialization_timeout=120",
        "-Dminimization_timeout=60",
        "-Dassertion_timeout=60",
        f"-Dextra_timeout={extra_timeout}",
        f"-Djunit_check_timeout={junit_check_timeout}",
        # METHODNOEXCEPTION must stay in the criteria for construction_fitness to
        # have anything to grade; keep the standard EvoSuite default set.
        "-Dcriterion=LINE:BRANCH:EXCEPTION:WEAKMUTATION:OUTPUT:METHOD:METHODNOEXCEPTION:CBRANCH",
    ]


def get_output_metadata_args(configuration_id, group_id):
    output_variables = ",".join(all_output_variables())
    return [
        f"-Dconfiguration_id={configuration_id}",
        f"-Dgroup_id={group_id}",
        f"-Doutput_variables={output_variables}",
    ]
