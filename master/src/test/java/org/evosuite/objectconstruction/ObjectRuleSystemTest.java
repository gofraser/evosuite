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

import java.lang.reflect.Method;
import java.util.Map;

import org.evosuite.EvoSuite;
import org.evosuite.Properties;
import org.evosuite.Properties.Algorithm;
import org.evosuite.Properties.Criterion;
import org.evosuite.SystemTestBase;
import org.evosuite.ga.metaheuristics.GeneticAlgorithm;
import org.evosuite.statistics.OutputVariable;
import org.evosuite.statistics.RuntimeVariable;
import org.evosuite.statistics.backend.DebugStatisticsBackend;
import org.evosuite.testsuite.TestSuiteChromosome;
import org.evosuite.utils.MethodUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.examples.with.different.packagename.objectconstruction.basicrules.Action;
import com.examples.with.different.packagename.objectconstruction.basicrules.BasicRules;
import com.examples.with.different.packagename.objectconstruction.basicrules.GameState;

/**
 * End-to-end system tests for EvoObj object-construction synthesis (the re-implementation plan §6.1,
 * enhancements E1 and E8). They run a full DynaMOSA search on the paper's running example
 * {@code BasicRules.checkRules(Action, GameState)} — a method guarded by object state (a
 * {@code Player[]} inside a {@code GameState}) that plain random generation struggles to establish —
 * and check that:
 *
 * <ul>
 *   <li>with {@code apply_object_rule=true} the search runs and reaches at least the coverage of the
 *       {@code apply_object_rule=false} baseline (tolerant, fixed seed, to avoid flakiness);</li>
 *   <li>whole-class mode (plan E1) works without {@code -Dtarget_method};</li>
 *   <li>the object-rule statistics (plan E8) are reported.</li>
 * </ul>
 */
public class ObjectRuleSystemTest extends SystemTestBase {

    private static String checkRulesSignature() {
        try {
            Method m = BasicRules.class.getMethod("checkRules", Action.class, GameState.class);
            return m.getName() + MethodUtil.getSignature(m);
        } catch (NoSuchMethodException e) {
            throw new AssertionError(e);
        }
    }

    /** Shared search configuration for a deterministic, reasonably quick object-rule run. */
    private void configureObjectRuleSearch() {
        Properties.STRATEGY = Properties.Strategy.MOSUITE;
        Properties.ALGORITHM = Algorithm.DYNAMOSA;
        Properties.CRITERION = new Criterion[] { Criterion.BRANCH };
        Properties.ASSERTIONS = false;
        Properties.MINIMIZE = false;
        // Modest budget keeps the system test quick while still exercising several generations of
        // template synthesis; the assertions are deliberately tolerant.
        Properties.SEARCH_BUDGET = 5000;
        Properties.RANDOM_SEED = 42L;
        // Keep legitimization from dominating the budget in a unit-test setting.
        Properties.INDIVIDUAL_LEGITIMIZATION_BUDGET = 3;
        Properties.TOTAL_LEGITIMIZATION_BUDGET = 10;
    }

    private TestSuiteChromosome runSearch(String targetClass) {
        // No -generateSuite: that flag forces the whole-suite strategy and would override
        // STRATEGY=MOSUITE needed to actually run DynaMOSA (cf. Issta14SystemTest).
        EvoSuite evosuite = new EvoSuite();
        Object result = evosuite.parseCommandLine(new String[] { "-class", targetClass });
        GeneticAlgorithm<TestSuiteChromosome> ga = getGAFromResult(result);
        return ga.getBestIndividual();
    }

    @Test
    public void objectRuleRunsEndToEndOnCheckRules() {
        configureObjectRuleSearch();
        Properties.TARGET_CLASS = BasicRules.class.getCanonicalName();
        Properties.TARGET_METHOD = checkRulesSignature();
        Properties.APPLY_OBJECT_RULE = true;

        TestSuiteChromosome best = runSearch(BasicRules.class.getCanonicalName());
        Assertions.assertNotNull(best, "the object-rule search should produce a suite");
        System.out.println("Object-rule coverage: " + best.getCoverage());
        Assertions.assertTrue(best.getCoverage() > 0.0,
                "the object-rule search should cover branches of checkRules");
    }

    @Test
    public void objectRuleAtLeastMatchesBaselineCoverage() {
        // Object rule first, on a clean state, so it gets its full benefit; checkRules needs a
        // constructed Player[] inside a GameState, which plain random generation rarely establishes.
        configureObjectRuleSearch();
        Properties.TARGET_CLASS = BasicRules.class.getCanonicalName();
        Properties.TARGET_METHOD = checkRulesSignature();
        Properties.APPLY_OBJECT_RULE = true;
        double withRule = runSearch(BasicRules.class.getCanonicalName()).getCoverage();

        // Restore the same clean state the harness gives each test method before the second search
        // (a single JVM otherwise accumulates state across full searches).
        resetStaticVariables();
        setDefaultPropertiesForTestCases();

        // Baseline: identical configuration (including the seed), object rule off.
        configureObjectRuleSearch();
        Properties.TARGET_CLASS = BasicRules.class.getCanonicalName();
        Properties.TARGET_METHOD = checkRulesSignature();
        Properties.APPLY_OBJECT_RULE = false;
        double baseline = runSearch(BasicRules.class.getCanonicalName()).getCoverage();

        System.out.println("objectRule=" + withRule + " baseline=" + baseline);
        // Tolerant (plan §6.1): the object rule must not do worse than the baseline. In practice it
        // covers substantially more of checkRules, but a strict inequality would be flaky.
        Assertions.assertTrue(withRule >= baseline - 0.05,
                "object rule should not regress coverage (objectRule=" + withRule + ", baseline=" + baseline + ")");
    }

    @Test
    public void wholeClassModeRunsWithoutTargetMethod() {
        configureObjectRuleSearch();
        Properties.TARGET_CLASS = BasicRules.class.getCanonicalName();
        Properties.TARGET_METHOD = ""; // no target method: whole-class mode (plan E1)
        Properties.APPLY_OBJECT_RULE = true;
        Properties.OBJECT_RULE_WHOLE_CLASS = true;

        TestSuiteChromosome best = runSearch(BasicRules.class.getCanonicalName());
        Assertions.assertNotNull(best, "whole-class object-rule search should produce a suite");
        System.out.println("Whole-class object-rule coverage: " + best.getCoverage());
        Assertions.assertTrue(best.getCoverage() > 0.0,
                "whole-class object rule should cover branches without a target method");
    }

    @Test
    public void objectRuleStatisticsAreReported() {
        configureObjectRuleSearch();
        Properties.TARGET_CLASS = BasicRules.class.getCanonicalName();
        Properties.TARGET_METHOD = checkRulesSignature();
        Properties.APPLY_OBJECT_RULE = true;
        Properties.OUTPUT_VARIABLES = RuntimeVariable.ObjectGraphBranches + ","
                + RuntimeVariable.ObjectGraphMaxSize + ","
                + RuntimeVariable.TemplateSynthesisTime + ","
                + RuntimeVariable.TemplateInjections + ","
                + RuntimeVariable.LegitimizationSuccessRate;

        runSearch(BasicRules.class.getCanonicalName());

        Map<String, OutputVariable<?>> map = DebugStatisticsBackend.getLatestWritten();
        Assertions.assertNotNull(map, "statistics should have been written");
        for (RuntimeVariable v : new RuntimeVariable[] {
                RuntimeVariable.ObjectGraphBranches, RuntimeVariable.ObjectGraphMaxSize,
                RuntimeVariable.TemplateSynthesisTime, RuntimeVariable.TemplateInjections,
                RuntimeVariable.LegitimizationSuccessRate }) {
            Assertions.assertNotNull(map.get(v.toString()), v + " should be reported");
        }
        // BasicRules.checkRules reads Player[] state -> at least one branch has a non-empty graph.
        Assertions.assertTrue(((Number) map.get(RuntimeVariable.ObjectGraphBranches.toString()).getValue()).intValue() > 0,
                "checkRules should have at least one branch with a non-empty object graph");
    }
}
