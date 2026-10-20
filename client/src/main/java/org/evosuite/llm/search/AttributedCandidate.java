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
package org.evosuite.llm.search;

import org.evosuite.testcase.TestChromosome;

import java.util.ArrayList;
import java.util.List;

/**
 * A candidate produced by an LLM feature together with the attribution of the attempt that
 * produced it, queued and drained as one entry so the two can never be separated.
 */
public final class AttributedCandidate {

    private final TestChromosome candidate;
    private final InjectionAttemptMetadata metadata;

    /**
     * @param candidate the produced test
     * @param metadata the attempt's attribution, null when there is none
     */
    public AttributedCandidate(TestChromosome candidate, InjectionAttemptMetadata metadata) {
        this.candidate = candidate;
        this.metadata = metadata;
    }

    public TestChromosome getCandidate() {
        return candidate;
    }

    /** The attempt's attribution, or null when there is none. */
    public InjectionAttemptMetadata getMetadata() {
        return metadata;
    }

    /** The candidates of {@code entries}, in order. */
    public static List<TestChromosome> candidatesOf(List<AttributedCandidate> entries) {
        List<TestChromosome> candidates = new ArrayList<>(entries.size());
        for (AttributedCandidate entry : entries) {
            candidates.add(entry.candidate);
        }
        return candidates;
    }
}
