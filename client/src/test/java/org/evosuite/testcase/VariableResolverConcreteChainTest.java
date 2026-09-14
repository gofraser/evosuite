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

import com.examples.with.different.packagename.ConcreteChainFixture.Inner;
import com.examples.with.different.packagename.ConcreteChainFixture.InnerImpl;
import com.examples.with.different.packagename.ConcreteChainFixture.Outer;
import com.examples.with.different.packagename.ConcreteChainFixture.OuterImpl;
import org.evosuite.Properties;
import org.evosuite.setup.TestCluster;
import org.evosuite.testcase.statements.FunctionalMockStatement;
import org.evosuite.utils.Randomness;
import org.evosuite.utils.generic.GenericClassFactory;
import org.evosuite.utils.generic.GenericConstructor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the per-chain (rather than per-dependency) mock/concrete decision described on
 * {@link GenerationContext#withConcreteChain()} and in the {@code effectiveMockProb} comment in
 * {@link VariableResolver#createObject}: once the resolver has committed to building a deep
 * interface/abstract dependency concretely, the dependencies of that concrete object must not
 * independently re-roll the 0.5 mock boost.
 */
public class VariableResolverConcreteChainTest {

    private static final boolean DEFAULT_MOCK_IF_NO_GENERATOR = Properties.MOCK_IF_NO_GENERATOR;
    private static final double DEFAULT_P_FUNCTIONAL_MOCKING = Properties.P_FUNCTIONAL_MOCKING;

    @AfterEach
    public void tearDown() {
        Properties.MOCK_IF_NO_GENERATOR = DEFAULT_MOCK_IF_NO_GENERATOR;
        Properties.P_FUNCTIONAL_MOCKING = DEFAULT_P_FUNCTIONAL_MOCKING;
        TestCluster.reset();
    }

    @Test
    public void concreteChainSuppressesMockBoostForNestedInterfaceDependency() throws Exception {
        long previousSeed = Randomness.getSeed();
        try {
            Properties.MOCK_IF_NO_GENERATOR = true;
            Properties.P_FUNCTIONAL_MOCKING = 0.0;

            GenericConstructor outerConstructor = new GenericConstructor(
                    OuterImpl.class.getConstructor(Inner.class), OuterImpl.class);
            GenericConstructor innerConstructor = new GenericConstructor(
                    InnerImpl.class.getConstructor(), InnerImpl.class);

            // Sanity check: without a concrete chain, a deep interface dependency (Inner, built
            // while satisfying OuterImpl's constructor parameter at depth 2) is boosted to a 0.5
            // mock probability, so across a handful of seeds at least one must be mocked.
            boolean sawMockWithoutChain = false;
            for (long seed = 0; seed < 50 && !sawMockWithoutChain; seed++) {
                TestCase test = buildOuter(outerConstructor, innerConstructor, seed,
                        GenerationContext.fromDepth(1));
                sawMockWithoutChain = containsFunctionalMock(test);
            }
            assertTrue(sawMockWithoutChain,
                    "Sanity check failed: the 0.5 mock boost never fired for the nested interface "
                            + "dependency over 50 seeds, so the rest of this test would be vacuous");

            // Same seeds, but the resolver is told it already committed to a concrete chain (as
            // it would after choosing OuterImpl over a mock for Outer itself). The nested Inner
            // dependency is then boosted less (0.25 instead of 0.5), so over the same seeds it
            // must be mocked strictly less often than without the chain.
            int mocksWithoutChain = 0;
            int mocksWithChain = 0;
            for (long seed = 0; seed < 200; seed++) {
                if (containsFunctionalMock(buildOuter(outerConstructor, innerConstructor, seed,
                        GenerationContext.fromDepth(1)))) {
                    mocksWithoutChain++;
                }
                if (containsFunctionalMock(buildOuter(outerConstructor, innerConstructor, seed,
                        GenerationContext.fromDepth(1).withConcreteChain()))) {
                    mocksWithChain++;
                }
            }
            assertTrue(mocksWithChain < mocksWithoutChain,
                    "Inside a concrete chain the nested interface dependency must be mocked less "
                            + "often (" + mocksWithChain + " vs " + mocksWithoutChain + " over 200 seeds)");
            assertTrue(mocksWithChain > 0,
                    "The boost is lowered inside a chain, not removed (" + mocksWithChain + " mocks)");
        } finally {
            Randomness.setSeed(previousSeed);
        }
    }

    private static TestCase buildOuter(GenericConstructor outerConstructor,
            GenericConstructor innerConstructor, long seed, GenerationContext context)
            throws Exception {
        TestCluster.reset();
        TestCluster cluster = TestCluster.getInstance();
        cluster.addGenerator(GenericClassFactory.get(Outer.class), outerConstructor);
        cluster.addGenerator(GenericClassFactory.get(Inner.class), innerConstructor);

        Randomness.setSeed(seed);
        TestCase test = new DefaultTestCase();
        TestFactory.getInstance().addConstructor(
                test, outerConstructor, Outer.class, 0, context);
        return test;
    }

    private static boolean containsFunctionalMock(TestCase test) {
        for (int position = 0; position < test.size(); position++) {
            if (test.getStatement(position) instanceof FunctionalMockStatement) {
                return true;
            }
        }
        return false;
    }
}
