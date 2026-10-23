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
package org.evosuite;

import org.evosuite.Properties.AssertionStrategy;
import org.evosuite.Properties.Criterion;
import org.evosuite.assertion.AssertionGenerator;
import org.evosuite.assertion.CompleteAssertionGenerator;
import org.evosuite.assertion.SimpleMutationAssertionGenerator;
import org.evosuite.assertion.UnitAssertionGenerator;
import org.evosuite.contracts.ContractChecker;
import org.evosuite.instrumentation.LinePool;
import org.evosuite.strategy.*;
import org.evosuite.symbolic.dse.DSEStrategy;
import org.evosuite.testsuite.TestSuiteChromosome;
import org.evosuite.utils.LoggingUtils;

import java.util.Arrays;

/**
 * Created by sina on 06/04/2017.
 */
public class TestSuiteGeneratorHelper {

    /**
     * <p>getCriterionDisplayName.</p>
     *
     * @param criterion a {@link org.evosuite.Properties.Criterion} object.
     * @return a {@link java.lang.String} object.
     */
    public static String getCriterionDisplayName(Criterion criterion) {
        switch (criterion) {
            case WEAKMUTATION:
                return "Mutation testing (weak)";
            case ONLYMUTATION:
                return "Only Mutation testing (weak)";
            case STRONGMUTATION:
            case MUTATION:
                return "Mutation testing (strong)";
            case DEFUSE:
                return "All DU Pairs";
            case STATEMENT:
                return "Statement Coverage";
            case ALLDEFS:
                return "All Definitions";
            case EXCEPTION:
                return "Exception";
            case ONLYBRANCH:
                return "Only-Branch Coverage";
            case METHODTRACE:
                return "Method Coverage";
            case METHOD:
                return "Top-Level Method Coverage";
            case METHODNOEXCEPTION:
                return "No-Exception Top-Level Method Coverage";
            case LINE:
                return "Line Coverage";
            case ONLYLINE:
                return "Only-Line Coverage";
            case OUTPUT:
                return "Method-Output Coverage";
            case INPUT:
                return "Method-Input Coverage";
            case BRANCH:
                return "Branch Coverage";
            case CBRANCH:
                return "Context Branch Coverage";
            case IBRANCH:
                return "Interprocedural Context Branch Coverage";
            case TRYCATCH:
                return "Try-Catch Branch Coverage";
            default:
                throw new IllegalArgumentException("Unrecognized criterion: " + criterion);
        }
    }

    static void printTestCriterion(Criterion criterion) {
        LoggingUtils.getEvoLogger().info("  - {}", getCriterionDisplayName(criterion));
    }

    static void printTestCriterion() {
        if (Properties.CRITERION.length > 1) {
            LoggingUtils.getEvoLogger().info("* " + ClientProcess.getPrettyPrintIdentifier() + "Test criteria:");
        } else {
            LoggingUtils.getEvoLogger().info("* " + ClientProcess.getPrettyPrintIdentifier() + "Test criterion:");
        }
        for (int i = 0; i < Properties.CRITERION.length; i++) {
            printTestCriterion(Properties.CRITERION[i]);
        }
    }

    static boolean isLineDebugInfoDependentCriterion(Criterion criterion) {
        return criterion == Criterion.LINE || criterion == Criterion.ONLYLINE;
    }

    static boolean hasUsableLineNumbersForTargetClass() {
        for (String className : LinePool.getKnownClasses()) {
            if (!isCUT(className)) {
                continue;
            }
            for (Integer line : LinePool.getLines(className)) {
                if (line != null && line.intValue() > 0) {
                    return true;
                }
            }
        }
        return false;
    }

    static Criterion[] removeDebugInfoDependentCriteriaIfMissing(Criterion[] criteria) {
        if (criteria == null || criteria.length == 0) {
            return criteria;
        }

        boolean hasDebugInfoDependentCriterion = Arrays.stream(criteria)
                .anyMatch(TestSuiteGeneratorHelper::isLineDebugInfoDependentCriterion);
        if (!hasDebugInfoDependentCriterion) {
            return criteria;
        }

        if (hasUsableLineNumbersForTargetClass()) {
            return criteria;
        }

        return Arrays.stream(criteria)
                .filter(c -> !isLineDebugInfoDependentCriterion(c))
                .toArray(Criterion[]::new);
    }

    private static boolean isCUT(String className) {
        return Properties.TARGET_CLASS.isEmpty()
                || className.equals(Properties.TARGET_CLASS)
                || className.startsWith(Properties.TARGET_CLASS + "$");
    }

    static TestGenerationStrategy getTestGenerationStrategy() {
        switch (Properties.STRATEGY) {
            case EVOSUITE:
                return new WholeTestSuiteStrategy();
            case RANDOM:
                return new RandomTestStrategy();
            case RANDOM_FIXED:
                return new FixedNumRandomTestStrategy();
            case MOSUITE:
                return new MOSuiteStrategy();
            case DSE:
                return new DSEStrategy();
            case NOVELTY:
                return new NoveltyStrategy();
            case MAP_ELITES:
                return new MAPElitesStrategy();
            case LLMSTRATEGY:
                return new LlmStrategy();
            default:
                throw new RuntimeException("Unsupported strategy: " + Properties.STRATEGY);
        }
    }

    /**
     * Add assertions to the generated test suite.
     *
     * @param tests the test suite to add assertions to
     */
    public static void addAssertions(TestSuiteChromosome tests) {
        AssertionGenerator asserter;
        ContractChecker.setActive(false);

        if (Properties.ASSERTION_STRATEGY == AssertionStrategy.MUTATION) {
            asserter = new SimpleMutationAssertionGenerator();
        } else if (Properties.ASSERTION_STRATEGY == AssertionStrategy.ALL) {
            asserter = new CompleteAssertionGenerator();
        } else {
            asserter = new UnitAssertionGenerator();
        }

        asserter.addAssertions(tests);

        if (Properties.FILTER_ASSERTIONS) {
            asserter.filterFailingAssertions(tests);
        }
    }
}
