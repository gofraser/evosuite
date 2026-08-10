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

import org.evosuite.coverage.branch.Branch;
import org.evosuite.testcase.TestCase;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Caches the best object-construction template synthesized for each branch. The fork re-ran
 * analysis and synthesis on every injection (its {@code templateTestMap} was written but never
 * read); this cache lets the factory occasionally reuse a proven template instead, which cuts
 * synthesis time for branches that are injected repeatedly.
 *
 * <p>The first structurally consistent template is retained. Entries for branches that become
 * covered are evicted via {@link #retainUncovered(Collection)}. Per-run state is reset by the
 * owning {@code ObjectRuleRuntime} for determinism.
 *
 * <p>Only the template test is cached, not the {@code PartialGraph}. The reuse path hands back a
 * clone of the finished template and never re-enters synthesis, so a cached graph would be
 * write-only, hence it is omitted.
 */
public class TemplateCache {

    private final Map<Branch, TestCase> bestTemplate = new LinkedHashMap<>();

    public TemplateCache() {
    }

    /** Clears all cached templates. */
    public void reset() {
        bestTemplate.clear();

    }

    /**
     * Stores the first template for a branch. The test is cloned so later mutation of the caller's
     * copy cannot corrupt the cache.
     */
    public void store(Branch branch, TestCase template) {
        if (branch == null || template == null) {
            return;
        }

        if (!bestTemplate.containsKey(branch)) {
            bestTemplate.put(branch, template.clone());

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

    /** Drops cached entries for branches no longer present in the uncovered set. */
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

            }
        }
    }
}
