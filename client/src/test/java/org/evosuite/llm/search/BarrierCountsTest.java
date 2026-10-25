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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BarrierCountsTest {

    @Test
    void timedOutInvocationWithoutExceptionIsNeitherSuccessNorException() {
        BarrierCounts counts = new BarrierCounts("k", "");
        counts.record(false, true, "call()", "", false);
        counts.record(false, false, "call()", "", false);
        counts.record(true, false, "call()", "IllegalStateException", true);

        assertEquals(3, counts.attempts);
        assertEquals(1, counts.successes);
        assertEquals(1, counts.exceptions);
        assertEquals(1, counts.failingInvocationsWithSuccessfulPrefix);
        assertEquals("call()", counts.label);
        assertEquals("IllegalStateException", counts.dominantException());
    }

    @Test
    void addSumsHistogramsSoTheOverallMostFrequentExceptionDominates() {
        BarrierCounts first = new BarrierCounts("k", "");
        first.record(true, false, "a()", "IllegalStateException", false);
        BarrierCounts second = new BarrierCounts("k", "");
        second.record(true, false, "b()", "NullPointerException", false);
        second.record(true, false, "b()", "NullPointerException", false);

        first.add(second);

        assertEquals(3, first.exceptions);
        assertEquals("NullPointerException", first.dominantException());
        assertEquals("b()", first.dominantFailingInvocationLabel());
    }

    @Test
    void combineKeepsTheFirstNonEmptyDominantExceptionAndLabel() {
        BarrierCounts current = new BarrierCounts("k", "");
        current.record(true, false, "a()", "IllegalStateException", false);
        BarrierCounts historical = new BarrierCounts("k", "");
        for (int i = 0; i < 3; i++) {
            historical.record(true, false, "b()", "NullPointerException", false);
        }
        BarrierCounts silent = new BarrierCounts("k", "");
        silent.record(false, false, "c()", "", false);

        BarrierCounts merged = new BarrierCounts("k", "");
        merged.combine(silent);
        merged.combine(current);
        merged.combine(historical);

        assertEquals(4, merged.exceptions);
        assertEquals(5, merged.attempts);
        assertEquals("IllegalStateException", merged.dominantException());
        assertEquals("a()", merged.dominantFailingInvocationLabel());
    }

    @Test
    void strongerEvidenceHasMoreExceptionsThenHigherBlockageThenMoreAttemptsThenSmallerKey() {
        assertTrue(counts("b", 4, 3).isStrongerThan(counts("a", 2, 2)));
        assertTrue(counts("b", 3, 3).isStrongerThan(counts("a", 4, 3)));
        assertFalse(counts("b", 4, 3).isStrongerThan(counts("a", 3, 3)));
        assertTrue(counts("a", 3, 3).isStrongerThan(counts("b", 3, 3)));
        assertFalse(counts("b", 3, 3).isStrongerThan(counts("a", 3, 3)));
        assertTrue(counts("a", 1, 1).isStrongerThan(null));
    }

    private static BarrierCounts counts(String key, int attempts, int exceptions) {
        BarrierCounts counts = new BarrierCounts(key, key);
        counts.attempts = attempts;
        counts.exceptions = exceptions;
        return counts;
    }
}
