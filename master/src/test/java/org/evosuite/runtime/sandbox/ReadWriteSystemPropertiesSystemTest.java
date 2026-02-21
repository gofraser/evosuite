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
package org.evosuite.runtime.sandbox;

import java.util.List;

import com.examples.with.different.packagename.sandbox.ReadTimezone;
import org.evosuite.EvoSuite;
import org.evosuite.Properties;
import org.evosuite.SystemTestBase;
import org.evosuite.ga.metaheuristics.GeneticAlgorithm;
import org.evosuite.junit.JUnitAnalyzer;
import org.evosuite.result.TestGenerationResult;
import org.evosuite.result.TestGenerationResultBuilder;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.execution.TestCaseExecutor;
import org.evosuite.testsuite.TestSuiteChromosome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import com.examples.with.different.packagename.sandbox.ReadWriteSystemProperties;

public class ReadWriteSystemPropertiesSystemTest extends SystemTestBase {

    private static final String userDir = System
            .getProperty(ReadWriteSystemProperties.USER_DIR);
    private static final String aProperty = System
            .getProperty(ReadWriteSystemProperties.A_PROPERTY);

    private final boolean DEFAULT_REPLACE_CALLS = Properties.REPLACE_CALLS;

    @AfterEach
    public void reset() {
        Properties.REPLACE_CALLS = DEFAULT_REPLACE_CALLS;
    }

    @BeforeAll
    public static void checkStatus() {
        Assumptions.assumeTrue(Sandbox.isSecurityManagerSupported());
        //such property shouldn't exist
        Assertions.assertNull(aProperty);
    }

    @Test
    public void testReadLineSeparator() {
        EvoSuite evosuite = new EvoSuite();

        String targetClass = ReadTimezone.class.getCanonicalName();

        Properties.TARGET_CLASS = targetClass;
        Properties.SANDBOX = true;
        Properties.REPLACE_CALLS = true;

        String[] command = new String[]{"-generateSuite", "-class",
                targetClass};

        Object result = evosuite.parseCommandLine(command);

        GeneticAlgorithm<TestSuiteChromosome> ga = getGAFromResult(result);
        TestSuiteChromosome best = ga.getBestIndividual();
        System.out.println("EvolvedTestSuite:\n" + best);
        double cov = best.getCoverage();
        Assertions.assertEquals(1d, cov, 0.001, "Non-optimal coverage: ");

        //now check the JUnit generation
        List<TestCase> list = best.getTests();
        int n = list.size();
        Assertions.assertTrue(n > 0);

        TestCaseExecutor.initExecutor(); //needed because it gets pulled down after the search

        try {
            Sandbox.initializeSecurityManagerForSUT();
            JUnitAnalyzer.removeTestsThatDoNotCompile(list);
        } finally {
            Sandbox.resetDefaultSecurityManager();
        }
        Assertions.assertEquals(n, list.size());

        TestGenerationResult tgr = TestGenerationResultBuilder.buildSuccessResult();
        String code = tgr.getTestSuiteCode();
        Assertions.assertTrue(code.contains("user.timezone"), "Test code:\n" + code);

        /*
         * This is tricky. The property 'debug' is read, but it does not exist.
         * Ideally, we should still have in the test case a call to be sure the variable
         * is set to null. But that would lead to a lot of problems :( eg cases
         * in which we end up in reading hundreds of thousands variables that do not exist
         */
        Assertions.assertFalse(code.contains("debug"), "Test code:\n" + code);
    }

    @Test
    public void testNoReplace() {
        Assumptions.assumeTrue(Sandbox.isSecurityManagerSupported());

        EvoSuite evosuite = new EvoSuite();

        String targetClass = ReadWriteSystemProperties.class.getCanonicalName();

        Properties.TARGET_CLASS = targetClass;
        Properties.SANDBOX = true;
        Properties.REPLACE_CALLS = false;

        String[] command = new String[]{"-generateSuite", "-class",
                targetClass};

        Object result = evosuite.parseCommandLine(command);

        GeneticAlgorithm<TestSuiteChromosome> ga = getGAFromResult(result);
        TestSuiteChromosome best = ga.getBestIndividual();
        System.out.println("EvolvedTestSuite:\n" + best);
        double cov = best.getCoverage();
        //without replace calls, we shouldn't be able to achieve full coverage
        Assertions.assertTrue(cov < 1d);
    }

    @Test
    public void testWithReplace() {

        EvoSuite evosuite = new EvoSuite();

        String targetClass = ReadWriteSystemProperties.class.getCanonicalName();

        Properties.TARGET_CLASS = targetClass;
        Properties.SANDBOX = true;
        Properties.REPLACE_CALLS = true;

        String[] command = new String[]{"-generateSuite", "-class",
                targetClass};

        Object result = evosuite.parseCommandLine(command);

        GeneticAlgorithm<TestSuiteChromosome> ga = getGAFromResult(result);
        TestSuiteChromosome best = ga.getBestIndividual();
        System.out.println("EvolvedTestSuite:\n" + best);
        double cov = best.getCoverage();
        Assertions.assertEquals(1d, cov, 0.001, "Non-optimal coverage: ");

        //now check if properties have been reset to their initial state
        String currentUserDir = System
                .getProperty(ReadWriteSystemProperties.USER_DIR);
        String currentAProperty = System
                .getProperty(ReadWriteSystemProperties.A_PROPERTY);

        Assertions.assertEquals(userDir, currentUserDir);
        Assertions.assertEquals(aProperty, currentAProperty);

        //now check the JUnit generation
        List<TestCase> list = best.getTests();
        int n = list.size();
        Assertions.assertTrue(n > 0);

        TestCaseExecutor.initExecutor(); //needed because it gets pulled down after the search

        try {
            Sandbox.initializeSecurityManagerForSUT();
            for (TestCase tc : list) {
                Assertions.assertFalse(tc.isUnstable());
            }

            JUnitAnalyzer.removeTestsThatDoNotCompile(list);
            Assertions.assertEquals(n, list.size());
            JUnitAnalyzer.handleTestsThatAreUnstable(list);
            Assertions.assertEquals(n, list.size());

            for (TestCase tc : list) {
                Assertions.assertFalse(tc.isUnstable());
            }

            Assertions.assertEquals(userDir, currentUserDir);
            Assertions.assertEquals(aProperty, currentAProperty);
        } finally {
            Sandbox.resetDefaultSecurityManager();
        }
    }
}
