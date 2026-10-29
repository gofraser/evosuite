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
package org.evosuite.result;

import org.evosuite.testcase.TestChromosome;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LightweightTestGenerationResultTest {

    private static class Source extends LightweightTestGenerationResult<TestChromosome> {
        private static final long serialVersionUID = 1L;

        @Override
        public String getErrorMessage() {
            throw new IllegalStateException("no message");
        }

        @Override
        public String getClassUnderTest() {
            return "com.example.Foo";
        }

        @Override
        public Set<Integer> getCoveredLines() {
            return new LinkedHashSet<>(Collections.singleton(7));
        }
    }

    @Test
    void failingGetterIsSkippedAndTheOthersAreCopied() {
        LightweightTestGenerationResult<TestChromosome> copy = LightweightTestGenerationResult.from(new Source());

        assertEquals("", copy.getErrorMessage());
        assertEquals("com.example.Foo", copy.getClassUnderTest());
        assertEquals(Collections.singleton(7), copy.getCoveredLines());
    }

    @Test
    void errorFromGetterIsNotSwallowed() {
        Source source = new Source() {
            private static final long serialVersionUID = 1L;

            @Override
            public String getClassUnderTest() {
                throw new OutOfMemoryError("simulated");
            }
        };

        assertThrows(OutOfMemoryError.class, () -> LightweightTestGenerationResult.from(source));
    }

    @Test
    void nullSourceGivesEmptySnapshot() {
        assertTrue(LightweightTestGenerationResult.from(null).getCoveredLines().isEmpty());
    }
}
