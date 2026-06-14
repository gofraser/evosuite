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
package org.evosuite.testcase;

import org.evosuite.llm.search.BlendChannel;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the descent-mark and blend-channel bookkeeping on
 * {@link TestChromosome}: explicit propagation semantics, the cap that keeps
 * the lowest (oldest) lineage ids, and the cleared-on-clone contract shared
 * with the other injection fields.
 */
class TestChromosomeDescentTest {

    private static TestChromosome chromosome() {
        TestChromosome tc = new TestChromosome();
        tc.setTestCase(new DefaultTestCase());
        return tc;
    }

    private static Set<Long> ids(long... values) {
        Set<Long> set = new LinkedHashSet<>();
        for (long v : values) {
            set.add(v);
        }
        return set;
    }

    @Test
    void descentMarksStartEmpty() {
        TestChromosome tc = chromosome();
        assertTrue(tc.getDescentLineageIds().isEmpty());
        assertTrue(tc.effectiveLineages().isEmpty());
        assertNull(tc.getBlendChannel());
    }

    @Test
    void addDescentLineagesMergesAndIgnoresInvalidIds() {
        TestChromosome tc = chromosome();
        tc.addDescentLineages(ids(2, 7));
        tc.addDescentLineages(Arrays.asList(null, -1L, 2L, 5L));
        assertEquals(ids(2, 5, 7), tc.getDescentLineageIds());
    }

    @Test
    void capKeepsLowestLineageIds() {
        TestChromosome tc = chromosome();
        tc.addDescentLineages(ids(10, 20, 30));
        tc.addDescentLineages(ids(5, 40, 50));
        assertEquals(TestChromosome.DESCENT_LINEAGE_CAP, tc.getDescentLineageIds().size());
        assertEquals(ids(5, 10, 20, 30), tc.getDescentLineageIds());
    }

    @Test
    void effectiveLineagesIncludeOwnInjectionLineage() {
        TestChromosome tc = chromosome();
        tc.addDescentLineages(ids(3));
        assertEquals(ids(3), tc.effectiveLineages());

        tc.setInjectionLineageId(9L);
        assertEquals(ids(3, 9), tc.effectiveLineages());

        TestChromosome injectedOnly = chromosome();
        injectedOnly.setInjectionLineageId(4L);
        assertEquals(Collections.singleton(4L), injectedOnly.effectiveLineages());
    }

    @Test
    void cloneClearsBlendChannelAndDescentLikeOtherInjectionFields() {
        TestChromosome tc = chromosome();
        tc.setInjectionSource(InjectionSource.LLM_ASYNC);
        tc.setInjectionLineageId(6L);
        tc.setInjectionGeneration(2);
        tc.setBlendChannel(BlendChannel.MUTANT);
        tc.addDescentLineages(ids(1, 6));

        TestChromosome clone = tc.clone();

        assertNull(clone.getInjectionSource());
        assertEquals(-1L, clone.getInjectionLineageId());
        assertEquals(-1, clone.getInjectionGeneration());
        assertNull(clone.getBlendChannel());
        assertTrue(clone.getDescentLineageIds().isEmpty(),
                "descent must be re-propagated explicitly at offspring birth, not cloned");
    }
}
