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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.evosuite.Properties;
import org.evosuite.coverage.branch.Branch;
import org.evosuite.graphs.interprocedural.InterproceduralGraphAnalysis;
import org.evosuite.graphs.interprocedural.ObjectConstructionContext;
import org.evosuite.graphs.interprocedural.interestednode.OCGInterestedNodeFilter;
import org.evosuite.graphs.interprocedural.var.DepVariable;
import org.evosuite.utils.Randomness;

/**
 * Chooses which uncovered branch the object-construction template should target. It
 * refines the fork's "pick any uncovered branch uniformly at random" by skipping work that cannot
 * pay off:
 *
 * <ul>
 *   <li><b>Eligibility</b>: a branch is only worth a template if its object-construction graph has
 *       at least one dependent state variable (a primitive-only branch has nothing for synthesis to
 *       build). Computed lazily and cached.</li>
 *   <li><b>Negative caching</b>: after {@value #MAX_FAILED_ATTEMPTS} synthesis attempts for a branch
 *       that never produced a template reaching the target call (legitimacy distance 0), the branch
 *       is marked ineligible for the rest of the run.</li>
 * </ul>
 *
 * <p>Selection among the remaining eligible branches stays uniformly random, so this only avoids
 * wasted synthesis — it does not otherwise change which branches the object rule helps cover. It is
 * always active when {@code apply_object_rule} is enabled; no separate flag. Per-run state is reset
 * from {@link ObjectConstructionContext#reset()} for determinism.
 */
public class ObjectRuleBranchSelector {

    /** Failed synthesis attempts after which a branch is negatively cached. */
    static final int MAX_FAILED_ATTEMPTS = 3;

    private static final ObjectRuleBranchSelector instance = new ObjectRuleBranchSelector();

    /** Cached "has a non-empty object graph" verdict per branch. */
    private final Map<Branch, Boolean> eligibilityCache = new LinkedHashMap<>();

    /** Consecutive synthesis attempts per branch that did not reach the target call. */
    private final Map<Branch, Integer> failedAttempts = new LinkedHashMap<>();

    /** Branches given up on after too many failed attempts. */
    private final Set<Branch> ineligible = new LinkedHashSet<>();

    /** Branches that have been eligible at least once in this run. */
    private final Set<Branch> everEligible = new LinkedHashSet<>();

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
            eligible = hasNonEmptyObjectGraph(branch);
        }
        eligibilityCache.put(branch, eligible);
        if (eligible) {
            everEligible.add(branch);
        }
        return eligible;
    }

    /**
     * Whole-class eligibility: the branch's own containing method must be a valid entry
     * method ({@link TargetContext#forBranch}), and, once its object graph is analyzed on demand,
     * the branch must depend on object state.
     */
    private boolean resolveWholeClassEligibility(Branch branch) {
        if (TargetContext.forBranch(branch) == null) {
            return false;
        }
        InterproceduralGraphAnalysis.analyzeBranchOnDemand(branch, new OCGInterestedNodeFilter());
        return hasNonEmptyObjectGraph(branch);
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

    /**
     * Picks a random eligible branch from the given uncovered branches, or {@code null} if none is
     * eligible (in which case the factory falls back to plain random generation for the test).
     */
    public Branch selectWorkingBranch(Collection<Branch> uncoveredBranches) {
        if (uncoveredBranches == null || uncoveredBranches.isEmpty()) {
            return null;
        }
        List<Branch> eligible = new ArrayList<>();
        for (Branch branch : uncoveredBranches) {
            if (isEligible(branch)) {
                eligible.add(branch);
            }
        }
        if (eligible.isEmpty()) {
            return null;
        }
        return Randomness.choice(eligible);
    }

    /**
     * Records the outcome of a synthesis attempt for a branch. Reaching the target call resets the
     * failure counter; repeated failures eventually mark the branch ineligible.
     */
    public void recordSynthesisAttempt(Branch branch, boolean reachedTargetCall) {
        if (branch == null) {
            return;
        }
        if (reachedTargetCall) {
            failedAttempts.remove(branch);
            return;
        }
        int attempts = failedAttempts.getOrDefault(branch, 0) + 1;
        failedAttempts.put(branch, attempts);
        if (attempts >= MAX_FAILED_ATTEMPTS) {
            ineligible.add(branch);
        }
    }

    public int getEverEligibleCount() {
        return everEligible.size();
    }

    public int getBlacklistedCount() {
        return ineligible.size();
    }
}
