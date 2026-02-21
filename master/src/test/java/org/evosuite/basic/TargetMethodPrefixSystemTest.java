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
package org.evosuite.basic;

import org.evosuite.EvoSuite;
import org.evosuite.Properties;
import org.evosuite.SystemTestBase;
import org.evosuite.ga.metaheuristics.GeneticAlgorithm;
import org.evosuite.strategy.TestGenerationStrategy;
import org.evosuite.testsuite.TestSuiteChromosome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.examples.with.different.packagename.TargetMethodPrefix;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class TargetMethodPrefixSystemTest extends SystemTestBase {

    private String targetMethod = "";
    private String targetMethodList = "";
    private String targetMethodPrefix = "";

    @BeforeEach
    public void backupValues() {
        targetMethod = Properties.TARGET_METHOD;
        targetMethodList = Properties.TARGET_METHOD_LIST;
        targetMethodPrefix = Properties.TARGET_METHOD_PREFIX;
        Properties.SEARCH_BUDGET = 50000;
    }

    @AfterEach
    public void restoreValues() {
        Properties.TARGET_METHOD = targetMethod;
        Properties.TARGET_METHOD_LIST = targetMethodList;
        Properties.TARGET_METHOD_PREFIX = targetMethodPrefix;
    }

    @Test
    public void testTotalBranchesInClass() {
        EvoSuite evosuite = new EvoSuite();

        String targetClass = TargetMethodPrefix.class.getCanonicalName();

        Properties.TARGET_CLASS = targetClass;
        Properties.TARGET_METHOD = "";
        Properties.TARGET_METHOD_LIST = "";
        Properties.TARGET_METHOD_PREFIX = "";

        String[] command = new String[]{"-generateSuite", "-class", targetClass};

        Object result = evosuite.parseCommandLine(command);
        GeneticAlgorithm<TestSuiteChromosome> ga = getGAFromResult(result);
        TestSuiteChromosome best = ga.getBestIndividual();

        int goals = TestGenerationStrategy.getFitnessFactories().get(0).getCoverageGoals().size(); // assuming single fitness function

        assertEquals(13, goals, "Wrong number of goals:");
        assertEquals(1d, best.getCoverage(), 0.001, "Non-optimal coverage: ");
    }

    @Test
    public void testTargetMethod() {
        EvoSuite evosuite = new EvoSuite();

        String targetClass = TargetMethodPrefix.class.getCanonicalName();
        String targetMethod = "foo(Ljava/lang/String;Ljava/lang/String;)Z";

        Properties.TARGET_CLASS = targetClass;
        Properties.TARGET_METHOD = targetMethod;
        Properties.TARGET_METHOD_LIST = "";
        Properties.TARGET_METHOD_PREFIX = "";

        String[] command = new String[]{"-generateSuite", "-class", targetClass,
                "-Dtarget_method=" + targetMethod, "-Dclient_on_thread=true"};

        Object result = evosuite.parseCommandLine(command);
        GeneticAlgorithm<TestSuiteChromosome> ga = getGAFromResult(result);
        TestSuiteChromosome best = ga.getBestIndividual();

        int goals = TestGenerationStrategy.getFitnessFactories().get(0).getCoverageGoals().size(); // assuming single fitness function

        assertEquals(4, goals, "Wrong number of goals: ");
        assertEquals(1d, best.getCoverage(), 0.001, "Non-optimal coverage: ");

    }

    @Test
    public void testTargetMethodPrefix1() {
        EvoSuite evosuite = new EvoSuite();

        String targetClass = TargetMethodPrefix.class.getCanonicalName();
        String targetMethodPrefix = "foo_";

        Properties.TARGET_CLASS = targetClass;
        Properties.TARGET_METHOD = "";
        Properties.TARGET_METHOD_LIST = "";
        Properties.TARGET_METHOD_PREFIX = targetMethodPrefix;

        String[] command = new String[]{"-generateSuite", "-class", targetClass,
                "-Dtarget_method_prefix=" + targetMethodPrefix, "-Dclient_on_thread=true"};

        Object result = evosuite.parseCommandLine(command);
        GeneticAlgorithm<TestSuiteChromosome> ga = getGAFromResult(result);
        TestSuiteChromosome best = ga.getBestIndividual();

        int goals = TestGenerationStrategy.getFitnessFactories().get(0).getCoverageGoals().size(); // assuming single fitness function

        assertEquals(4, goals, "Wrong number of goals: ");
        assertEquals(1d, best.getCoverage(), 0.001, "Non-optimal coverage: ");

    }

    @Test
    public void testTargetMethodPrefix2() {
        EvoSuite evosuite = new EvoSuite();

        String targetClass = TargetMethodPrefix.class.getCanonicalName();
        String targetMethodPrefix = "foo"; // different from foo_

        Properties.TARGET_CLASS = targetClass;
        Properties.TARGET_METHOD = "";
        Properties.TARGET_METHOD_LIST = "";
        Properties.TARGET_METHOD_PREFIX = targetMethodPrefix;

        String[] command = new String[]{"-generateSuite", "-class", targetClass,
                "-Dtarget_method_prefix=" + targetMethodPrefix, "-Dclient_on_thread=true"};

        Object result = evosuite.parseCommandLine(command);
        GeneticAlgorithm<TestSuiteChromosome> ga = getGAFromResult(result);
        TestSuiteChromosome best = ga.getBestIndividual();

        int goals = TestGenerationStrategy.getFitnessFactories().get(0).getCoverageGoals().size(); // assuming single fitness function

        assertEquals(8, goals, "Wrong number of goals: ");
        assertEquals(1d, best.getCoverage(), 0.001, "Non-optimal coverage: ");

        Properties.TARGET_METHOD_PREFIX = "";
    }
}
