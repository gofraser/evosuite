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

import org.evosuite.Properties;
import org.evosuite.ga.ConstructionFailedException;
import org.evosuite.seeding.ObjectPoolManager;
import org.evosuite.testcase.statements.ConstructorStatement;
import org.evosuite.testcase.statements.Statement;
import org.evosuite.testcase.statements.numeric.IntPrimitiveStatement;
import org.evosuite.utils.generic.GenericClassFactory;
import org.evosuite.utils.generic.GenericConstructor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A pooled sequence whose insertion fails part-way must be removed from where it was inserted,
 * not from the end of the test, which would delete the test's own statements.
 */
public class VariableResolverObjectPoolRollbackTest {

    public static class Pooled {
        public Pooled() {
        }
    }

    private double objectPoolProbability;
    private double nullProbability;

    @BeforeEach
    public void setUp() {
        objectPoolProbability = Properties.P_OBJECT_POOL;
        nullProbability = Properties.NULL_PROBABILITY;
        Properties.P_OBJECT_POOL = 1.0;
        Properties.NULL_PROBABILITY = 0.0;
        ObjectPoolManager.getInstance().reset();
    }

    @AfterEach
    public void tearDown() {
        Properties.P_OBJECT_POOL = objectPoolProbability;
        Properties.NULL_PROBABILITY = nullProbability;
        ObjectPoolManager.getInstance().reset();
    }

    @Test
    public void failedSequenceInsertionKeepsTheTestsOwnStatements() throws Exception {
        DefaultTestCase sequence = new DefaultTestCase();
        sequence.addStatement(new ConstructorStatement(sequence,
                new GenericConstructor(Pooled.class.getConstructor(), Pooled.class),
                Collections.emptyList()));
        sequence.addStatement(new IntPrimitiveStatement(sequence, 7) {
            private static final long serialVersionUID = 1L;

            @Override
            public Statement copy(TestCase newTestCase, int offset) {
                throw new IllegalStateException("copy refused");
            }
        });
        ObjectPoolManager.getInstance().addSequence(GenericClassFactory.get(Pooled.class), sequence);

        DefaultTestCase test = new DefaultTestCase();
        Statement first = new IntPrimitiveStatement(test, 1);
        Statement last = new IntPrimitiveStatement(test, 2);
        test.addStatement(first);
        test.addStatement(last);

        VariableResolver resolver = new VariableResolver(TestFactory.getInstance());
        VariableResolutionConfig config = new VariableResolutionConfig.Builder()
                .withAllowNull(false)
                .withCanUseMocks(false)
                .withCanReuseExistingVariables(false)
                .build();
        try {
            // Insert the pooled sequence in front of the last statement; its second statement fails to copy.
            resolver.attemptGeneration(test, Pooled.class, 1, new GenerationContext(), config);
        } catch (ConstructionFailedException e) {
            // The fallback generator may fail too; only the rollback matters here.
        }

        // Compare identities without assertSame: rendering a corrupted test for the message would throw.
        assertTrue(first == test.getStatement(0), "the rollback removed the test's own first statement");
        assertTrue(last == test.getStatement(test.size() - 1),
                "the rollback removed the test's own last statement");
        for (int i = 0; i < test.size(); i++) {
            assertEquals(i, test.getStatement(i).getReturnValue().getStPosition());
        }
    }
}
