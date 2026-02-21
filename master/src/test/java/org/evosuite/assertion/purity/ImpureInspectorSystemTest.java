/*
 * Copyright (C) 2010-2018 Gordon Fraser, Andrea Arcuri and EvoSuite
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
package org.evosuite.assertion.purity;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.evosuite.EvoSuite;
import org.evosuite.Properties;
import org.evosuite.SystemTestBase;
import org.evosuite.assertion.CheapPurityAnalyzer;
import org.evosuite.ga.metaheuristics.GeneticAlgorithm;
import org.evosuite.statistics.OutputVariable;
import org.evosuite.statistics.RuntimeVariable;
import org.evosuite.statistics.backend.DebugStatisticsBackend;
import org.evosuite.testsuite.TestSuiteChromosome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Type;

import com.examples.with.different.packagename.purity.ImpureInspector;

public class ImpureInspectorSystemTest extends SystemTestBase {
    private final boolean DEFAULT_RESET_STATIC_FIELDS = Properties.RESET_STATIC_FIELDS;
    private final Properties.JUnitCheckValues DEFAULT_JUNIT_CHECK = Properties.JUNIT_CHECK;
    private final boolean DEFAULT_JUNIT_TESTS = Properties.JUNIT_TESTS;
    private final boolean DEFAULT_PURE_INSPECTORS = Properties.PURE_INSPECTORS;
    private final boolean DEFAULT_SANDBOX = Properties.SANDBOX;

    @BeforeEach
    public void saveProperties() {
        Properties.SANDBOX = true;
        Properties.RESET_STATIC_FIELDS = true;
        Properties.JUNIT_CHECK = Properties.JUnitCheckValues.TRUE;
        Properties.JUNIT_TESTS = true;
        Properties.PURE_INSPECTORS = true;
    }

    @AfterEach
    public void restoreProperties() {
        Properties.RESET_STATIC_FIELDS = DEFAULT_RESET_STATIC_FIELDS;
        Properties.JUNIT_CHECK = DEFAULT_JUNIT_CHECK;
        Properties.JUNIT_TESTS = DEFAULT_JUNIT_TESTS;
        Properties.PURE_INSPECTORS = DEFAULT_PURE_INSPECTORS;
        Properties.SANDBOX = DEFAULT_SANDBOX;
    }

    @Test
    public void test() {
        EvoSuite evosuite = new EvoSuite();

        String targetClass = ImpureInspector.class.getCanonicalName();
        Properties.TARGET_CLASS = targetClass;
        Properties.OUTPUT_VARIABLES = "" + RuntimeVariable.HadUnstableTests;
        String[] command = new String[]{"-generateSuite", "-class",
                targetClass};

        Object result = evosuite.parseCommandLine(command);

        GeneticAlgorithm<TestSuiteChromosome> ga = getGAFromResult(result);
        TestSuiteChromosome best = ga.getBestIndividual();
        System.out.println("EvolvedTestSuite:\n" + best);
        double best_fitness = best.getFitness();
        Assertions.assertEquals(0.0, best_fitness, 0.0, "Optimal coverage was not achieved ");

        CheapPurityAnalyzer purityAnalyzer = CheapPurityAnalyzer.getInstance();

        String descriptor = Type.getMethodDescriptor(Type.INT_TYPE);

        boolean recursivePureFunction = purityAnalyzer.isPure(targetClass,
                "recursivePureFunction",
                Type.getMethodDescriptor(Type.INT_TYPE, Type.INT_TYPE));
        assertTrue(recursivePureFunction);

        boolean getImpureValue = purityAnalyzer.isPure(targetClass,
                "getImpureValue", descriptor);
        assertFalse(getImpureValue);

        boolean getPureValue = purityAnalyzer.isPure(targetClass,
                "getPureValue", descriptor);
        assertTrue(getPureValue);

        boolean getImpureValueFromCall = purityAnalyzer.isPure(targetClass,
                "getImpureValueFromCall", descriptor);
        assertFalse(getImpureValueFromCall);

        boolean getPureValueFromCall = purityAnalyzer.isPure(targetClass,
                "getPureValueFromCall", descriptor);
        assertTrue(getPureValueFromCall);

        boolean recursivePureInspector = purityAnalyzer.isPure(targetClass,
                "recursivePureInspector", descriptor);
        assertTrue(recursivePureInspector);

        boolean recursiveImpureInspector = purityAnalyzer.isPure(targetClass,
                "recursiveImpureInspector", descriptor);
        assertFalse(recursiveImpureInspector);

        Map<String, OutputVariable<?>> map = DebugStatisticsBackend.getLatestWritten();
        Assertions.assertNotNull(map);
        OutputVariable unstable = map.get(RuntimeVariable.HadUnstableTests.toString());
        Assertions.assertNotNull(unstable);
        Assertions.assertEquals(Boolean.FALSE, unstable.getValue());
    }

}
