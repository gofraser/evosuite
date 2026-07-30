/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 *
 * This file is part of EvoSuite.
 *
 * EvoSuite is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published
 * by the Free Software Foundation, either version 3.0 of the License, or
 * (at your option) any later version.
 *
 * EvoSuite is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with EvoSuite. If not, see http://www.gnu.org/licenses/.
 */
package org.evosuite.testcase.synthesizer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.evosuite.Properties;
import org.evosuite.TimeController;
import org.evosuite.coverage.branch.Branch;
import org.evosuite.graphs.interprocedural.InterproceduralGraphAnalysis;
import org.evosuite.graphs.interprocedural.ObjectConstructionContext;
import org.evosuite.graphs.interprocedural.interestednode.OCGInterestedNodeFilter;
import org.evosuite.graphs.interprocedural.var.DepVariable;

/**
 * Chooses which uncovered branch the object-construction template should target. It
 * refines the fork's "pick any uncovered branch uniformly at random" by skipping work that cannot
 * pay off:
 *
 * <ul>
 *   <li><b>Eligibility</b>: a branch is only worth a template if its object-construction graph has
 *       at least one dependent state variable (a primitive-only branch has nothing for synthesis to
 *       build). Computed lazily and cached.</li>
 *   <li><b>Negative caching</b>: after {@code object_rule_max_failed_attempts} discarded synthesis
 *       attempts for a branch, the branch is marked ineligible for the rest of the run. With
 *       A structurally consistent template still counts as a failure when it throws before the
 *       target call. This prevents a cloneable but useless prefix from resetting the failure streak
 *       and being synthesized indefinitely.</li>
 * </ul>
 *
 * <p>Selection among the remaining eligible branches stays uniformly random, so this only avoids
 * wasted synthesis — it does not otherwise change which branches the object rule helps cover. It is
 * always active when {@code apply_object_rule} is enabled; no separate flag. Per-run state is reset
 * from {@link ObjectConstructionContext#reset()} for determinism.
 */
public class ObjectRuleBranchSelector {

    private static final ObjectRuleBranchSelector instance = new ObjectRuleBranchSelector();

    /** Cached "has a non-empty object graph" verdict per branch. */
    private final Map<Branch, Boolean> eligibilityCache = new LinkedHashMap<>();

    /** Consecutive synthesis attempts per branch that did not produce a usable template. */
    private final Map<Branch, Integer> failedAttempts = new LinkedHashMap<>();

    /** Branches given up on after too many failed attempts. */
    private final Set<Branch> ineligible = new LinkedHashSet<>();

    /** Branches that have been eligible at least once in this run. */
    private final Set<Branch> everEligible = new LinkedHashSet<>();

    /** Fresh synthesis attempts per targeted branch (telemetry only). */
    private final Map<Branch, Integer> synthesisAttempts = new LinkedHashMap<>();

    /** Branches that have experienced at least two consecutive failed synthesis attempts. */
    private final Set<Branch> repeatedFailureBranches = new LinkedHashSet<>();

    /** Largest consecutive-failure streak observed for any branch. */
    private int maxConsecutiveFailures;

    /**
     * RNG used only to randomize lazy eligibility discovery. It must not consume EvoSuite's main
     * search stream: for classes with no eligible graph (for example CIDR), discovery is a no-op
     * and should leave ordinary generation identical to baseline.
     */
    private Random discoveryRandom = new Random(0L);

    private ObjectRuleBranchSelector() {
    }

    public static ObjectRuleBranchSelector getInstance() {
        return instance;
    }

    /** Clears all per-run caches. Invoked from {@link ObjectConstructionContext#reset()}. */
    public void reset() {
        eligibilityCache.clear();
        failedAttempts.clear();
        ineligible.clear();
        everEligible.clear();
        synthesisAttempts.clear();
        repeatedFailureBranches.clear();
        maxConsecutiveFailures = 0;
        discoveryRandom = new Random(Properties.RANDOM_SEED == null ? 0L : Properties.RANDOM_SEED);
    }

    /**
     * Whether the branch is worth a synthesis attempt: not negatively cached and backed by a
     * non-empty object-construction graph.
     */
    public boolean isEligible(Branch branch) {
        if (branch == null || ineligible.contains(branch)) {
            return false;
        }
        Boolean cached = eligibilityCache.get(branch);
        if (cached != null) {
            return cached;
        }
        boolean eligible;
        if (Properties.OBJECT_RULE_WHOLE_CLASS
                && (Properties.TARGET_METHOD == null || Properties.TARGET_METHOD.isEmpty())) {
            eligible = resolveWholeClassEligibility(branch);
        } else {
            eligible = hasConstructibleObjectGraph(branch);
        }
        eligibilityCache.put(branch, eligible);
        if (eligible) {
            everEligible.add(branch);
        }
        return eligible;
    }

    /**
     * Resolves and caches eligibility for the complete branch search space.
     *
     * <p>This is deliberately separate from {@link #selectWorkingBranch(Collection)} so search
     * strategies can perform the potentially expensive interprocedural graph discovery during
     * initialization, before the effective search budget starts. Once this method returns,
     * selection among the supplied branches is cache-only and cannot spend search time expanding
     * object-construction graphs.</p>
     *
     * @param branches all branches that may later be presented for selection
     */
    public void prepareEligibility(Collection<Branch> branches) {
        if (branches == null || branches.isEmpty()) {
            return;
        }
        for (Branch branch : branches) {
            if (branch != null) {
                isEligible(branch);
            }
        }
    }

    /**
     * Whole-class eligibility: the branch's own containing method must be a valid entry
     * method ({@link TargetContext#forBranch}), and, once its object graph is analyzed on demand,
     * the branch must depend on object state.
     */
    private boolean resolveWholeClassEligibility(Branch branch) {
        TargetContext context = TargetContext.forBranch(branch);
        if (context == null) {
            ObjectConstructionGraphDiagnostics.recordEligibilityDecision(
                    branch, null, null, false, TargetContext.rejectionReasonForBranch(branch));
            return false;
        }
        InterproceduralGraphAnalysis.analyzeBranchOnDemand(branch, new OCGInterestedNodeFilter());
        TargetContext previous = TargetContext.getActive();
        TargetContext.setActive(context);
        try {
            return evaluateConstructibleObjectGraph(branch, context);
        } finally {
            if (previous == null) {
                TargetContext.clearActive();
            } else {
                TargetContext.setActive(previous);
            }
        }
    }

    private boolean hasNonEmptyObjectGraph(Branch branch) {
        for (Map<Branch, Set<DepVariable>> perBranch : ObjectConstructionContext.branchInterestedVarsMap.values()) {
            Set<DepVariable> vars = perBranch.get(branch);
            if (vars != null && !vars.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private boolean hasConstructibleObjectGraph(Branch branch) {
        return evaluateConstructibleObjectGraph(branch, TargetContext.getActive());
    }

    private boolean evaluateConstructibleObjectGraph(Branch branch, TargetContext context) {
        if (!hasNonEmptyObjectGraph(branch)) {
            ObjectConstructionGraphDiagnostics.recordEligibilityDecision(
                    branch, context, null, false, "no_object_state_dependency");
            return false;
        }
        PartialGraph graph = new ConstructionPathSynthesizer()
                .constructPartialComputationGraph(branch);
        if (!graph.hasExternallyConstructibleState()) {
            ObjectConstructionGraphDiagnostics.recordEligibilityDecision(
                    branch, context, graph, false, "no_externally_constructible_state");
            return false;
        }
        if (!graph.hasPotentialSynthesisAction()) {
            ObjectConstructionGraphDiagnostics.recordEligibilityDecision(
                    branch, context, graph, false, "no_runtime_synthesis_action");
            return false;
        }
        if (graph.hasDependencyCycle()) {
            ObjectConstructionGraphDiagnostics.recordEligibilityDecision(
                    branch, context, graph, false, "dependency_cycle");
            return false;
        }
        ObjectConstructionGraphDiagnostics.recordEligibilityDecision(
                branch, context, graph, true, "eligible");
        return true;
    }

    /**
     * Picks a random eligible branch from the given uncovered branches, or {@code null} if none is
     * eligible (in which case the factory falls back to plain random generation for the test).
     *
     * <p>Whole-class eligibility can require an interprocedural analysis of the branch's entry
     * method. Do not eagerly analyze every uncovered branch merely to build a candidate list: on
     * branch-heavy classes that can consume the entire search budget before the first generation.
     * Already-discovered eligible branches are reused until they are covered or negatively cached;
     * only then is the unknown remainder shuffled and scanned to the next eligible branch. This
     * makes analysis genuinely on demand and prevents the initial population from repeatedly
     * expanding unrelated graphs.</p>
     */
    public Branch selectWorkingBranch(Collection<Branch> uncoveredBranches) {
        if (uncoveredBranches == null || uncoveredBranches.isEmpty()) {
            return null;
        }
        List<Branch> eligible = new ArrayList<>();
        List<Branch> unknown = new ArrayList<>();
        for (Branch branch : uncoveredBranches) {
            if (branch != null && !ineligible.contains(branch)) {
                Boolean cached = eligibilityCache.get(branch);
                if (Boolean.TRUE.equals(cached)) {
                    eligible.add(branch);
                } else if (cached == null) {
                    unknown.add(branch);
                }
            }
        }
        if (!eligible.isEmpty()) {
            return eligible.get(discoveryRandom.nextInt(eligible.size()));
        }

        Collections.shuffle(unknown, discoveryRandom);
        for (Branch branch : unknown) {
            if (!TimeController.getInstance().isThereStillTimeInThisPhase()) {
                return null;
            }
            if (isEligible(branch)) {
                return branch;
            }
        }
        return null;
    }

    /**
     * Whether the supplied search space still contains an eligible or not-yet-classified branch.
     * Once this returns {@code false} for the complete uncovered branch set, EvoObj cannot become
     * active later in the run and the GA can bypass its per-generation bookkeeping.
     */
    public boolean hasPotentialCandidate(Collection<Branch> branches) {
        if (branches == null || branches.isEmpty()) {
            return false;
        }
        for (Branch branch : branches) {
            if (branch == null || ineligible.contains(branch)) {
                continue;
            }
            Boolean eligible = eligibilityCache.get(branch);
            if (eligible == null || eligible) {
                return true;
            }
        }
        return false;
    }

    /**
     * Backwards-compatible feedback for callers which only know whether the template was useful.
     */
    public void recordSynthesisAttempt(Branch branch, boolean producedUsableTemplate) {
        recordSynthesisAttempt(branch, producedUsableTemplate, producedUsableTemplate);
    }

    /**
     * Records the outcome of a synthesis attempt. Reaching the selected method is necessary for a
     * structurally usable template, but is not evidence that object synthesis covered its working
     * branch. A merely improved template remains available to the current GA population once, but
     * only actual working-goal coverage resets the failure streak. This prevents deterministic
     * setup recipes from lowering the same fitness on every fresh synthesis and thereby bypassing
     * the bounded negative cache indefinitely.
     */
    public void recordSynthesisAttempt(
            Branch branch, boolean reachedTargetCall, boolean coveredWorkingGoal) {
        if (branch == null) {
            return;
        }
        synthesisAttempts.put(branch, synthesisAttempts.getOrDefault(branch, 0) + 1);
        if (reachedTargetCall && coveredWorkingGoal) {
            failedAttempts.remove(branch);
            return;
        }
        recordRetryableFailure(branch);
    }

    /**
     * Records a failed entry-construction attempt that may succeed with another registered
     * generator or with a receiver produced by ordinary generation. This shares the bounded
     * negative-cache policy with synthesis failures, but is not counted as a synthesis attempt.
     */
    public void recordRetryableEntryConstructionFailure(Branch branch) {
        if (branch != null) {
            recordRetryableFailure(branch);
        }
    }

    private void recordRetryableFailure(Branch branch) {
        int attempts = failedAttempts.getOrDefault(branch, 0) + 1;
        failedAttempts.put(branch, attempts);
        maxConsecutiveFailures = Math.max(maxConsecutiveFailures, attempts);
        if (attempts >= 2) {
            repeatedFailureBranches.add(branch);
        }
        if (attempts >= Properties.OBJECT_RULE_MAX_FAILED_ATTEMPTS) {
            ineligible.add(branch);
        }
    }

    /**
     * Permanently rejects a branch for which no receiver generator or callable entry exists.
     * Randomized failures with known generators use
     * {@link #recordRetryableEntryConstructionFailure(Branch)} instead.
     */
    public void recordEntryConstructionFailure(Branch branch) {
        if (branch == null) {
            return;
        }
        eligibilityCache.put(branch, false);
        ineligible.add(branch);
    }

    public int getEverEligibleCount() {
        return everEligible.size();
    }

    public int getBlacklistedCount() {
        return ineligible.size();
    }

    public int getTargetedBranchCount() {
        return synthesisAttempts.size();
    }

    public int getMaxInjectionsPerBranch() {
        int max = 0;
        for (Integer attempts : synthesisAttempts.values()) {
            max = Math.max(max, attempts);
        }
        return max;
    }

    public int getRepeatedFailureBranchCount() {
        return repeatedFailureBranches.size();
    }

    public int getMaxConsecutiveFailures() {
        return maxConsecutiveFailures;
    }
}
