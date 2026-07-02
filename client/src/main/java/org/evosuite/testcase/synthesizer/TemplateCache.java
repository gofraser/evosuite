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

import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.evosuite.coverage.branch.Branch;
import org.evosuite.graphs.interprocedural.ObjectConstructionContext;
import org.evosuite.testcase.TestCase;

/**
 * Caches the best object-construction template synthesized for each branch (plan E4). The fork
 * re-ran analysis, synthesis and legitimization on every injection (its {@code templateTestMap} was
 * written but never read); this cache lets the factory occasionally reuse a proven template instead,
 * which cuts synthesis time (visible in the {@code TemplateSynthesisTime} statistic) for branches
 * that are injected repeatedly.
 *
 * <p>"Best" means the template with the lowest legitimacy distance seen so far (a template that
 * reaches the target call, distance 0, is ideal). Entries for branches that become covered are
 * evicted via {@link #retainUncovered(Collection)}. Per-run state is reset from
 * {@link ObjectConstructionContext#reset()} for determinism (plan E7).
 *
 * <p>Deviation from the plan: only the template test is cached, not the {@code PartialGraph}. The
 * reuse path hands back a clone of the finished template and never re-enters synthesis or
 * legitimization, so a cached graph would be write-only — the exact anti-pattern the plan's §7
 * warns against — hence it is omitted.
 */
public class TemplateCache {

    private static final TemplateCache instance = new TemplateCache();

    private final Map<Branch, TestCase> bestTemplate = new LinkedHashMap<>();
    private final Map<Branch, Double> bestDistance = new LinkedHashMap<>();

    private TemplateCache() {
    }

    public static TemplateCache getInstance() {
        return instance;
    }

    /** Clears all cached templates. Invoked from {@link ObjectConstructionContext#reset()}. */
    public void reset() {
        bestTemplate.clear();
        bestDistance.clear();
    }

    /**
     * Stores a template for the branch if it is the first one or improves on (is no worse than) the
     * best legitimacy distance recorded so far. The test is cloned so later mutation of the caller's
     * copy cannot corrupt the cache.
     */
    public void store(Branch branch, TestCase template, double legitimacyDistance) {
        if (branch == null || template == null) {
            return;
        }
        Double previousBest = bestDistance.get(branch);
        if (previousBest == null || legitimacyDistance <= previousBest) {
            bestTemplate.put(branch, template.clone());
            bestDistance.put(branch, legitimacyDistance);
        }
    }

    public boolean hasTemplate(Branch branch) {
        return branch != null && bestTemplate.containsKey(branch);
    }

    /**
     * Returns a fresh clone of the cached template for the branch (so the main GA can mutate it
     * without affecting the cache), or {@code null} if nothing is cached.
     */
    public TestCase getClonedTemplate(Branch branch) {
        TestCase cached = branch == null ? null : bestTemplate.get(branch);
        return cached == null ? null : cached.clone();
    }

    /** Drops cached entries for branches no longer present in the uncovered set (plan E4). */
    public void retainUncovered(Collection<Branch> uncoveredBranches) {
        if (uncoveredBranches == null) {
            return;
        }
        Set<Branch> keep = new HashSet<>(uncoveredBranches);
        Iterator<Map.Entry<Branch, TestCase>> it = bestTemplate.entrySet().iterator();
        while (it.hasNext()) {
            Branch branch = it.next().getKey();
            if (!keep.contains(branch)) {
                it.remove();
                bestDistance.remove(branch);
            }
        }
    }
}
