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
package org.evosuite.mock.java.net;

import com.examples.with.different.packagename.mock.java.net.*;

import org.evosuite.EvoSuite;
import org.evosuite.Properties;
import org.evosuite.SystemTestBase;
import org.evosuite.coverage.TestFitnessFactory;
import org.evosuite.ga.metaheuristics.GeneticAlgorithm;
import org.evosuite.strategy.TestGenerationStrategy;
import org.evosuite.testcase.TestFitnessFunction;
import org.evosuite.testsuite.TestSuiteChromosome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Created by arcuri on 12/19/14.
 */
public class MockTcpSystemTest extends SystemTestBase {

    private static final boolean VNET = Properties.VIRTUAL_NET;

    @AfterEach
    public void restoreProperties() {
        Properties.VIRTUAL_NET = VNET;
    }


    @Test
    public void testReceiveTcp_exception_onlyLine() {
        EvoSuite evosuite = new EvoSuite();


        String targetClass = ReceiveTcp_exception.class.getCanonicalName();

        Properties.TARGET_CLASS = targetClass;
        Properties.SEARCH_BUDGET = 20000;
        Properties.VIRTUAL_NET = true;

        Properties.CRITERION = new Properties.Criterion[]{
                Properties.Criterion.LINE,
        };


        String[] command = new String[]{"-generateSuite", "-class", targetClass};

        Object result = evosuite.parseCommandLine(command);
        Assertions.assertNotNull(result);

        GeneticAlgorithm<TestSuiteChromosome> ga = getGAFromResult(result);
        TestSuiteChromosome best = ga.getBestIndividual();
        System.out.println("EvolvedTestSuite:\n" + best);

        // should be only one test, as any exception thrown would lead to lower coverage
        Assertions.assertEquals(1, best.getTests().size());
        Assertions.assertEquals(1d, best.getCoverage(), 0.001, "Non-optimal coverage: ");
    }

    @Test
    public void testReceiveTcp_exception_tryCatch() {
        EvoSuite evosuite = new EvoSuite();


        String targetClass = ReceiveTcp_exception_tryCatch.class.getCanonicalName();

        Properties.TARGET_CLASS = targetClass;
        Properties.SEARCH_BUDGET = 100_000;
        Properties.VIRTUAL_NET = true;

        Properties.CRITERION = new Properties.Criterion[]{
                Properties.Criterion.LINE,
        };


        String[] command = new String[]{"-generateSuite", "-class", targetClass};

        Object result = evosuite.parseCommandLine(command);
        Assertions.assertNotNull(result);

        GeneticAlgorithm<TestSuiteChromosome> ga = getGAFromResult(result);
        TestSuiteChromosome best = ga.getBestIndividual();
        System.out.println("EvolvedTestSuite:\n" + best);

        /*
            there should be two tests:
            - that covers the catch block (which has a return)
            - one with no exception
          */
        Assertions.assertEquals(2, best.getTests().size());
        Assertions.assertEquals(1d, best.getCoverage(), 0.001, "Non-optimal coverage: ");
    }

    @Test
    public void testReceiveTcp_exception() {
        EvoSuite evosuite = new EvoSuite();


        String targetClass = ReceiveTcp_exception.class.getCanonicalName();

        Properties.TARGET_CLASS = targetClass;
        Properties.SEARCH_BUDGET = 20000;
        Properties.VIRTUAL_NET = true;

        Properties.CRITERION = new Properties.Criterion[]{
                Properties.Criterion.LINE,
                Properties.Criterion.EXCEPTION
        };


        String[] command = new String[]{"-generateSuite", "-class", targetClass};

        Object result = evosuite.parseCommandLine(command);
        Assertions.assertNotNull(result);

        GeneticAlgorithm<TestSuiteChromosome> ga = getGAFromResult(result);
        TestSuiteChromosome best = ga.getBestIndividual();
        System.out.println("EvolvedTestSuite:\n" + best);

        /*
            should be 2 tests:
            - one with exception
            - one with full line coverage

            fitness for line: 0
            fitness for exception:  1/(1+1) = 0.5
          */
        Assertions.assertEquals(2, best.getTests().size());
        Assertions.assertEquals(0.5d, best.getFitness(), 0.001, "Unexpected fitness: ");
    }


    //TODO put back once we properly handle boolean functions with TT
    @Disabled
    @Test
    public void testReceiveTcp_noBranch() {
        EvoSuite evosuite = new EvoSuite();

        /*
            as there is no branch, covering both boolean outputs with online line coverage
            would not be possible. and so output coverage would be a necessity
         */
        String targetClass = ReceiveTcp_noBranch.class.getCanonicalName();

        Properties.TARGET_CLASS = targetClass;
        Properties.SEARCH_BUDGET = 20000;
        Properties.VIRTUAL_NET = true;

        Properties.CRITERION = new Properties.Criterion[]{
                Properties.Criterion.LINE,
                Properties.Criterion.OUTPUT
        };

        String[] command = new String[]{"-generateSuite", "-class", targetClass};

        Object result = evosuite.parseCommandLine(command);
        Assertions.assertNotNull(result);

        GeneticAlgorithm<TestSuiteChromosome> ga = getGAFromResult(result);
        TestSuiteChromosome best = ga.getBestIndividual();
        System.out.println("EvolvedTestSuite:\n" + best);

        List<TestFitnessFactory<? extends TestFitnessFunction>> list = TestGenerationStrategy.getFitnessFactories();

        Assertions.assertEquals(2, list.size());
        Assertions.assertEquals(1d, best.getCoverage(), 0.001, "Non-optimal coverage: ");
    }


    @Test
    public void testReceiveTcp() {
        EvoSuite evosuite = new EvoSuite();

        String targetClass = ReceiveTcp.class.getCanonicalName();

        Properties.TARGET_CLASS = targetClass;
        Properties.SEARCH_BUDGET = 200_000;
        Properties.VIRTUAL_NET = true;

        String[] command = new String[]{"-generateSuite", "-class", targetClass};

        Object result = evosuite.parseCommandLine(command);
        Assertions.assertNotNull(result);

        GeneticAlgorithm<TestSuiteChromosome> ga = getGAFromResult(result);
        TestSuiteChromosome best = ga.getBestIndividual();
        System.out.println("EvolvedTestSuite:\n" + best);

        int goals = TestGenerationStrategy.getFitnessFactories().get(0).getCoverageGoals().size(); // assuming single fitness function
        Assertions.assertEquals(3, goals, "Wrong number of goals: ");
        Assertions.assertEquals(1d, best.getCoverage(), 0.001, "Non-optimal coverage: ");
    }

    @Test
    public void testSendTcp() {
        EvoSuite evosuite = new EvoSuite();

        String targetClass = SendTcp.class.getCanonicalName();

        Properties.TARGET_CLASS = targetClass;
        Properties.SEARCH_BUDGET = 20000;
        Properties.VIRTUAL_NET = true;

        String[] command = new String[]{"-generateSuite", "-class", targetClass};

        Object result = evosuite.parseCommandLine(command);
        Assertions.assertNotNull(result);

        GeneticAlgorithm<TestSuiteChromosome> ga = getGAFromResult(result);
        TestSuiteChromosome best = ga.getBestIndividual();
        System.out.println("EvolvedTestSuite:\n" + best);

        int goals = TestGenerationStrategy.getFitnessFactories().get(0).getCoverageGoals().size(); // assuming single fitness function
        Assertions.assertEquals(3, goals, "Wrong number of goals: ");
        Assertions.assertEquals(1d, best.getCoverage(), 0.001, "Non-optimal coverage: ");
    }

}
