/**
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
 * License along with EvoSuite. If not, see <http://www.gnu.org/licenses/>.
 */
package org.evosuite.executionmode;

import org.evosuite.Properties.Strategy;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link TestGeneration#parseStrategyFromJavaOpts}.
 */
class TestGenerationStrategyParsingTest {

    @Test
    void noStrategyOptionReturnsNull() {
        assertNull(TestGeneration.parseStrategyFromJavaOpts(
                Arrays.asList("-Dfoo=bar", "-Dbaz=qux")));
    }

    @Test
    void emptyListReturnsNull() {
        assertNull(TestGeneration.parseStrategyFromJavaOpts(Collections.emptyList()));
    }

    @Test
    void exactEnumNameIsParsed() {
        assertEquals(Strategy.LLMSTRATEGY,
                TestGeneration.parseStrategyFromJavaOpts(
                        Collections.singletonList("-Dstrategy=LLMSTRATEGY")));
    }

    @Test
    void caseInsensitiveLowerCase() {
        assertEquals(Strategy.LLMSTRATEGY,
                TestGeneration.parseStrategyFromJavaOpts(
                        Collections.singletonList("-Dstrategy=llmstrategy")));
    }

    @Test
    void caseInsensitiveMixedCase() {
        assertEquals(Strategy.LLMSTRATEGY,
                TestGeneration.parseStrategyFromJavaOpts(
                        Collections.singletonList("-Dstrategy=LlmStrategy")));
    }

    @Test
    void existingStrategiesParseCaseInsensitively() {
        assertEquals(Strategy.ENTBUG,
                TestGeneration.parseStrategyFromJavaOpts(
                        Collections.singletonList("-Dstrategy=entbug")));
        assertEquals(Strategy.NOVELTY,
                TestGeneration.parseStrategyFromJavaOpts(
                        Collections.singletonList("-Dstrategy=Novelty")));
        assertEquals(Strategy.MAP_ELITES,
                TestGeneration.parseStrategyFromJavaOpts(
                        Collections.singletonList("-Dstrategy=map_elites")));
    }

    @Test
    void invalidThenValidResolvesToValid() {
        // First entry is unrecognized; second is valid — should return the valid one
        assertEquals(Strategy.LLMSTRATEGY,
                TestGeneration.parseStrategyFromJavaOpts(
                        Arrays.asList("-Dstrategy=BOGUS", "-Dstrategy=LLMSTRATEGY")));
    }

    @Test
    void allInvalidReturnsNull() {
        assertNull(TestGeneration.parseStrategyFromJavaOpts(
                Arrays.asList("-Dstrategy=NONEXISTENT", "-Dstrategy=ALSO_BAD")));
    }

    @Test
    void firstValidWins() {
        // Two valid entries — returns the first one encountered
        assertEquals(Strategy.NOVELTY,
                TestGeneration.parseStrategyFromJavaOpts(
                        Arrays.asList("-Dstrategy=NOVELTY", "-Dstrategy=LLMSTRATEGY")));
    }

    @Test
    void whitespaceAroundValueIsTrimmed() {
        assertEquals(Strategy.EVOSUITE,
                TestGeneration.parseStrategyFromJavaOpts(
                        Collections.singletonList("-Dstrategy= EVOSUITE ")));
    }

    @Test
    void nonStrategyOptsAreIgnored() {
        assertEquals(Strategy.LLMSTRATEGY,
                TestGeneration.parseStrategyFromJavaOpts(
                        Arrays.asList("-Dfoo=bar", "-Dstrategy=LLMSTRATEGY", "-Dbaz=qux")));
    }
}
