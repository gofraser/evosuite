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
package org.evosuite.objectconstruction;

import org.evosuite.EvoSuite;
import org.evosuite.Properties;
import org.evosuite.Properties.Algorithm;
import org.evosuite.Properties.Criterion;
import org.evosuite.SystemTestBase;
import org.evosuite.ga.metaheuristics.GeneticAlgorithm;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.factories.RandomLengthTestFactory;
import org.evosuite.testsuite.TestSuiteChromosome;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.examples.with.different.packagename.objectconstruction.parseargs.ArgsParser;

/**
 * Regression + reproduction test for the object-construction array-element synthesis defect.
 *
 * <p>{@code ArgsParser} (vendored from SF110 90_dcparseargs) branches on elements read from two
 * same-typed {@code String[]} fields. On the cluster this reliably drove EvoObj's array-element
 * synthesis to emit an internally inconsistent test (an array-index assignment whose backing array
 * reference was missing), which then threw {@code AssertionError: VariableReference not found in
 * TestCase} while cloning and aborted the whole client with seed 42.
 *
 * <p>This test pins that behaviour to a small, dependency-free class as a permanent regression
 * guard: the whole-class object-rule search runs to completion and every test in the produced suite
 * is cloneable -- i.e. no inconsistent test escapes synthesis into the population.
 *
 * <p>It also reports {@link RandomLengthTestFactory#inconsistentTemplatesDiscarded}, the count of
 * templates the factory rejected as inconsistent. That counter is the observability hook for the
 * still-open synthesizer root cause in {@code ArrayElementVariableWrapper}: when the underlying bug
 * fires the factory increments it and falls back. Note it typically reads 0 here -- EvoSuite system
 * tests run the client in-thread, so the seed-42 trajectory diverges from the separate-JVM client
 * that reproduced the defect on the cluster in ~18s. The faithful reproduction is therefore an
 * out-of-process run of the evosuite jar against dcparseargs.jar; this in-tree test guards the fix
 * and gives the future root-cause fix a concrete signal to drive to 0.
 */
public class ArraySynthesisSystemTest extends SystemTestBase {

    @Test
    public void wholeClassObjectRuleProducesOnlyCloneableTests() {
        Properties.STRATEGY = Properties.Strategy.MOSUITE;
        Properties.ALGORITHM = Algorithm.DYNAMOSA;
        // Match the cluster criterion set: more criteria means many more branches carry a non-empty
        // object graph, which is what exercises the array-element synthesis path often enough to
        // surface the defect.
        Properties.CRITERION = new Criterion[] { Criterion.LINE, Criterion.BRANCH, Criterion.EXCEPTION,
                Criterion.WEAKMUTATION, Criterion.OUTPUT, Criterion.METHOD, Criterion.METHODNOEXCEPTION,
                Criterion.CBRANCH };
        Properties.ASSERTIONS = false;
        Properties.MINIMIZE = false;
        Properties.SEARCH_BUDGET = 20;
        Properties.RANDOM_SEED = 42L;
        Properties.INDIVIDUAL_LEGITIMIZATION_BUDGET = 3;
        Properties.TOTAL_LEGITIMIZATION_BUDGET = 10;

        Properties.TARGET_CLASS = ArgsParser.class.getCanonicalName();
        Properties.TARGET_METHOD = ""; // whole-class mode
        Properties.APPLY_OBJECT_RULE = true;
        Properties.OBJECT_RULE_WHOLE_CLASS = true;

        org.evosuite.TestGenerationContext.getInstance().getObjectRuleSession()
                .resetInconsistentTemplatesDiscarded();

        EvoSuite evosuite = new EvoSuite();
        Object result = evosuite.parseCommandLine(
                new String[] { "-class", ArgsParser.class.getCanonicalName() });
        GeneticAlgorithm<TestSuiteChromosome> ga = getGAFromResult(result);
        TestSuiteChromosome best = ga.getBestIndividual();

        Assertions.assertNotNull(best, "the array-synthesis object-rule search should produce a suite");

        // Every test that made it into the suite must be internally consistent: cloning is exactly
        // the operation that throws on a dangling array reference, so this is the permanent guard
        // that no inconsistent synthesized test escaped into the population.
        for (TestChromosome tc : best.getTestChromosomes()) {
            Assertions.assertDoesNotThrow(() -> tc.getTestCase().clone(),
                    "a test in the produced suite is not cloneable (dangling reference escaped synthesis)");
        }

        System.out.println("ArgsParser inconsistentTemplatesDiscarded="
                + org.evosuite.TestGenerationContext.getInstance().getObjectRuleSession()
                        .getInconsistentTemplatesDiscarded()
                + " (observability hook; see class Javadoc for why this is usually 0 in-process)");
    }
}
