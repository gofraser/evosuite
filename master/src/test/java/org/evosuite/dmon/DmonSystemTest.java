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
package org.evosuite.dmon;

import com.examples.with.different.packagename.dmon.ConstructorBottleneck;
import org.evosuite.EvoSuite;
import org.evosuite.Properties;
import org.evosuite.SystemTestBase;
import org.evosuite.ga.metaheuristics.GeneticAlgorithm;
import org.evosuite.testsuite.TestSuiteChromosome;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class DmonSystemTest extends SystemTestBase {

    @Test
    public void dmonImprovesCoverageOnConstructorInitializationBottleneck() throws Exception {
        double withoutDmon = runCoverage(false);
        double withDmon = runCoverage(true);

        Assertions.assertTrue(withDmon > withoutDmon,
                "Expected DMoN to improve coverage, without=" + withoutDmon + ", with=" + withDmon);
        Assertions.assertTrue(withDmon > 0.0,
                "Expected non-zero coverage with DMoN enabled, got " + withDmon);
    }

    private double runCoverage(boolean dmonEnabled) throws Exception {
        EvoSuite evosuite = new EvoSuite();
        String targetClass = ConstructorBottleneck.class.getCanonicalName();

        Properties.TARGET_CLASS = targetClass;
        Properties.CRITERION = new Properties.Criterion[]{Properties.Criterion.LINE};
        Properties.SEARCH_BUDGET = 2000;
        Properties.HANDLE_STATIC_FIELDS = false;

        // Keep baseline generation from solving interface setup via regular mocking.
        Properties.P_FUNCTIONAL_MOCKING = 0.0;
        Properties.MOCK_IF_NO_GENERATOR = false;
        Properties.FUNCTIONAL_MOCKING_PERCENT = 1.0;

        Properties.getInstance().setValue("dmon_enabled", dmonEnabled);
        Properties.getInstance().setValue("dmon_only_target_class_constructor", true);
        Properties.getInstance().setValue("dmon_helpful_npe_parse", true);
        Properties.getInstance().setValue("dmon_asm_fallback", true);
        Properties.getInstance().setValue("dmon_allow_reflection_fallback", true);
        Properties.getInstance().setValue("dmon_promote_in_place", true);
        Properties.getInstance().setValue("dmon_validate_promoted_once", false);

        String[] command = new String[]{"-generateSuite", "-class", targetClass};
        Object result = evosuite.parseCommandLine(command);
        GeneticAlgorithm<TestSuiteChromosome> ga = getGAFromResult(result);
        TestSuiteChromosome best = ga.getBestIndividual();
        return best.getCoverage();
    }
}
