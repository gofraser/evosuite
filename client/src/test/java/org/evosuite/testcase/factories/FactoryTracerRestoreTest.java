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
package org.evosuite.testcase.factories;

import org.evosuite.Properties;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.execution.ExecutionTracer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FactoryTracerRestoreTest {

    private boolean tracerWasEnabled;
    private boolean objectRuleWasApplied;

    @BeforeEach
    void setUp() {
        tracerWasEnabled = ExecutionTracer.isEnabled();
        objectRuleWasApplied = Properties.APPLY_OBJECT_RULE;
        Properties.APPLY_OBJECT_RULE = false;
        ExecutionTracer.enable();
    }

    @AfterEach
    void tearDown() {
        Properties.APPLY_OBJECT_RULE = objectRuleWasApplied;
        if (tracerWasEnabled) {
            ExecutionTracer.enable();
        } else {
            ExecutionTracer.disable();
        }
    }

    @Test
    void randomLengthFactoryReenablesTracerWhenGenerationFails() {
        RandomLengthTestFactory factory = new RandomLengthTestFactory() {
            @Override
            protected TestCase getNewTestCase() {
                throw new IllegalStateException("generation failed");
            }
        };

        assertThrows(IllegalStateException.class, factory::getChromosome);

        assertTrue(ExecutionTracer.isEnabled());
    }

    @Test
    void allMethodsFactoryReenablesTracerWhenGenerationFails() {
        AllMethodsTestChromosomeFactory factory = new AllMethodsTestChromosomeFactory() {
            @Override
            protected TestCase getNewTestCase() {
                throw new IllegalStateException("generation failed");
            }
        };

        assertThrows(IllegalStateException.class, factory::getChromosome);

        assertTrue(ExecutionTracer.isEnabled());
    }
}
