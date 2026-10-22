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
package org.evosuite.strategy;

import com.examples.with.different.packagename.FlagExample1;
import org.evosuite.Properties;
import org.evosuite.Properties.Criterion;
import org.evosuite.classpath.ClassPathHandler;
import org.evosuite.ga.metaheuristics.GeneticAlgorithm;
import org.evosuite.ga.metaheuristics.SearchListener;
import org.evosuite.ga.stoppingconditions.StoppingCondition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Pins what the four search factories wire into the algorithm they assemble: the listeners, the
 * stopping conditions with their limits, and how the dynamic limit rewrites the search budget.
 * The algorithm keeps both collections in hash sets, so they are compared sorted by name.
 */
class GAFactoryAssemblyTest {

    @BeforeEach
    void setUp() {
        Properties.getInstance().resetToDefaults();
        Properties.SELECTED_JUNIT = "";
        // MAP-Elites derives its features from the class under test when it is constructed.
        Properties.TARGET_CLASS = FlagExample1.class.getName();
        ClassPathHandler.getInstance().changeTargetCPtoTheSameAsEvoSuite();
        // Keep the shutdown wiring out of the way unless a test asks for it.
        Properties.SHUTDOWN_HOOK = false;
        Properties.NEW_STATISTICS = false;
    }

    @AfterEach
    void tearDown() {
        Properties.resetTargetClass();
        Properties.getInstance().resetToDefaults();
    }

    @SuppressWarnings("unchecked")
    private static String describe(GeneticAlgorithm<?> ga) throws Exception {
        Field field = GeneticAlgorithm.class.getDeclaredField("listeners");
        field.setAccessible(true);
        List<String> listeners = new ArrayList<>();
        for (SearchListener<?> l : (Collection<SearchListener<?>>) field.get(ga)) {
            listeners.add(l.getClass().getSimpleName());
        }
        Collections.sort(listeners);
        List<String> conditions = new ArrayList<>();
        for (StoppingCondition<?> c : ga.getStoppingConditions()) {
            conditions.add(c.getClass().getSimpleName() + "=" + c.getLimit());
        }
        Collections.sort(conditions);
        return "listeners=" + listeners + " stop=" + conditions;
    }

    private static String suite() throws Exception {
        return describe(new PropertiesSuiteGAFactory().getSearchAlgorithm());
    }

    private static String test() throws Exception {
        Properties.STRATEGY = Properties.Strategy.ONEBRANCH;
        return describe(new PropertiesTestGAFactory().getSearchAlgorithm());
    }

    private static String novelty() throws Exception {
        Properties.STRATEGY = Properties.Strategy.NOVELTY;
        return describe(new PropertiesNoveltySearchFactory().getSearchAlgorithm());
    }

    private static String mapElites() throws Exception {
        Properties.STRATEGY = Properties.Strategy.MAP_ELITES;
        return describe(new PropertiesMapElitesSearchFactory().getSearchAlgorithm());
    }

    @Test
    void defaults() throws Exception {
        assertEquals("listeners=[RelativeSuiteLengthBloatControl] stop=[MaxTimeStoppingCondition=60, ZeroFitnessStoppingCondition=0]", suite());
        assertEquals("listeners=[MaxGenerationStoppingCondition, MaxTimeStoppingCondition, RelativeTestLengthBloatControl, TestCaseRecycler, ZeroFitnessStoppingCondition] stop=[MaxTimeStoppingCondition=60, ZeroFitnessStoppingCondition=0]", test());
        assertEquals("listeners=[MaxGenerationStoppingCondition, MaxTimeStoppingCondition, RelativeSuiteLengthBloatControl, ResourceController, ZeroFitnessStoppingCondition] stop=[MaxTimeStoppingCondition=60, ZeroFitnessStoppingCondition=0]", novelty());
        assertEquals("listeners=[MaxGenerationStoppingCondition, MaxTimeStoppingCondition, RelativeSuiteLengthBloatControl, ResourceController, ZeroFitnessStoppingCondition] stop=[MaxTimeStoppingCondition=60, ZeroFitnessStoppingCondition=0]", mapElites());
    }

    @Test
    void nonTimeStoppingConditionAddsGlobalTime() throws Exception {
        Properties.STOPPING_CONDITION = Properties.StoppingCondition.MAXGENERATIONS;
        assertEquals("listeners=[RelativeSuiteLengthBloatControl] stop=[GlobalTimeStoppingCondition=120, MaxGenerationStoppingCondition=60, ZeroFitnessStoppingCondition=0]", suite());
        assertEquals("listeners=[GlobalTimeStoppingCondition, MaxGenerationStoppingCondition, MaxGenerationStoppingCondition, RelativeTestLengthBloatControl, TestCaseRecycler, ZeroFitnessStoppingCondition] stop=[GlobalTimeStoppingCondition=120, MaxGenerationStoppingCondition=60, ZeroFitnessStoppingCondition=0]", test());
        assertEquals("listeners=[GlobalTimeStoppingCondition, MaxGenerationStoppingCondition, MaxGenerationStoppingCondition, RelativeSuiteLengthBloatControl, ResourceController, ZeroFitnessStoppingCondition] stop=[GlobalTimeStoppingCondition=120, MaxGenerationStoppingCondition=60, ZeroFitnessStoppingCondition=0]", novelty());
        assertEquals("listeners=[GlobalTimeStoppingCondition, MaxGenerationStoppingCondition, MaxGenerationStoppingCondition, RelativeSuiteLengthBloatControl, ResourceController, ZeroFitnessStoppingCondition] stop=[GlobalTimeStoppingCondition=120, MaxGenerationStoppingCondition=60, ZeroFitnessStoppingCondition=0]", mapElites());
    }

    @Test
    void stopZeroOff() throws Exception {
        Properties.STOP_ZERO = false;
        assertEquals("listeners=[RelativeSuiteLengthBloatControl] stop=[MaxTimeStoppingCondition=60]", suite());
        assertEquals("listeners=[MaxGenerationStoppingCondition, MaxTimeStoppingCondition, RelativeTestLengthBloatControl, TestCaseRecycler] stop=[MaxTimeStoppingCondition=60]", test());
        assertEquals("listeners=[MaxGenerationStoppingCondition, MaxTimeStoppingCondition, RelativeSuiteLengthBloatControl, ResourceController] stop=[MaxTimeStoppingCondition=60]", novelty());
        assertEquals("listeners=[MaxGenerationStoppingCondition, MaxTimeStoppingCondition, RelativeSuiteLengthBloatControl, ResourceController] stop=[MaxTimeStoppingCondition=60]", mapElites());
    }

    @Test
    void dynamicLimitScalesTheBudgetAndTheLimit() throws Exception {
        Properties.DYNAMIC_LIMIT = true;
        Properties.STOPPING_CONDITION = Properties.StoppingCondition.MAXGENERATIONS;
        assertEquals("listeners=[RelativeSuiteLengthBloatControl] stop=[GlobalTimeStoppingCondition=120, MaxGenerationStoppingCondition=60, ZeroFitnessStoppingCondition=0]", suite());
        assertEquals(300L, Properties.SEARCH_BUDGET);
        Properties.SEARCH_BUDGET = 60;
        Properties.STRATEGY = Properties.Strategy.EVOSUITE;
        Properties.TEST_FACTORY = Properties.TestFactory.RANDOM;
        Properties.ALGORITHM = Properties.Algorithm.MONOTONIC_GA;
        assertEquals("listeners=[GlobalTimeStoppingCondition, MaxGenerationStoppingCondition, MaxGenerationStoppingCondition, RelativeSuiteLengthBloatControl, ResourceController, ZeroFitnessStoppingCondition] stop=[GlobalTimeStoppingCondition=120, MaxGenerationStoppingCondition=300, ZeroFitnessStoppingCondition=0]", suite());
        assertEquals(300L, Properties.SEARCH_BUDGET);
        Properties.SEARCH_BUDGET = 60;
        assertEquals("listeners=[GlobalTimeStoppingCondition, MaxGenerationStoppingCondition, MaxGenerationStoppingCondition, RelativeTestLengthBloatControl, TestCaseRecycler, ZeroFitnessStoppingCondition] stop=[GlobalTimeStoppingCondition=120, MaxGenerationStoppingCondition=300, ZeroFitnessStoppingCondition=0]", test());
        assertEquals(300L, Properties.SEARCH_BUDGET);
        Properties.SEARCH_BUDGET = 60;
        assertEquals("listeners=[GlobalTimeStoppingCondition, MaxGenerationStoppingCondition, MaxGenerationStoppingCondition, RelativeSuiteLengthBloatControl, ResourceController, ZeroFitnessStoppingCondition] stop=[GlobalTimeStoppingCondition=120, MaxGenerationStoppingCondition=300, ZeroFitnessStoppingCondition=0]", novelty());
        assertEquals(300L, Properties.SEARCH_BUDGET);
        Properties.SEARCH_BUDGET = 60;
        assertEquals("listeners=[GlobalTimeStoppingCondition, MaxGenerationStoppingCondition, MaxGenerationStoppingCondition, RelativeSuiteLengthBloatControl, ResourceController, ZeroFitnessStoppingCondition] stop=[GlobalTimeStoppingCondition=120, MaxGenerationStoppingCondition=300, ZeroFitnessStoppingCondition=0]", mapElites());
        assertEquals(300L, Properties.SEARCH_BUDGET);
    }

    @Test
    void shutdownHookAddsShutdownConditions() throws Exception {
        Properties.SHUTDOWN_HOOK = true;
        assertEquals("listeners=[RelativeSuiteLengthBloatControl] stop=[MaxTimeStoppingCondition=60, RMIStoppingCondition=0, ShutdownTestWriter=0, ZeroFitnessStoppingCondition=0]", suite());
        assertEquals("listeners=[MaxGenerationStoppingCondition, MaxTimeStoppingCondition, RelativeTestLengthBloatControl, TestCaseRecycler, ZeroFitnessStoppingCondition] stop=[MaxTimeStoppingCondition=60, ZeroFitnessStoppingCondition=0]", test());
        assertEquals("listeners=[MaxGenerationStoppingCondition, MaxTimeStoppingCondition, RMIStoppingCondition, RelativeSuiteLengthBloatControl, ResourceController, ShutdownTestWriter, ZeroFitnessStoppingCondition] stop=[MaxTimeStoppingCondition=60, RMIStoppingCondition=0, ShutdownTestWriter=0, ZeroFitnessStoppingCondition=0]", novelty());
        assertEquals("listeners=[MaxGenerationStoppingCondition, MaxTimeStoppingCondition, RMIStoppingCondition, RelativeSuiteLengthBloatControl, ResourceController, ShutdownTestWriter, ZeroFitnessStoppingCondition] stop=[MaxTimeStoppingCondition=60, RMIStoppingCondition=0, ShutdownTestWriter=0, ZeroFitnessStoppingCondition=0]", mapElites());
    }

    @Test
    void mutationCriterionTimeout() throws Exception {
        Properties.CRITERION = new Criterion[]{Criterion.BRANCH, Criterion.STRONGMUTATION};
        assertEquals("listeners=[MaxGenerationStoppingCondition, MaxTimeStoppingCondition, MutationTimeoutStoppingCondition, RelativeTestLengthBloatControl, TestCaseRecycler, ZeroFitnessStoppingCondition] stop=[MaxTimeStoppingCondition=60, MutationTimeoutStoppingCondition=3, ZeroFitnessStoppingCondition=0]", test());
        Properties.STRATEGY = Properties.Strategy.ONEBRANCH;
        assertEquals("listeners=[MaxGenerationStoppingCondition, MaxTimeStoppingCondition, MutationTimeoutStoppingCondition, RelativeSuiteLengthBloatControl, ResourceController, ZeroFitnessStoppingCondition] stop=[MaxTimeStoppingCondition=60, MutationTimeoutStoppingCondition=3, ZeroFitnessStoppingCondition=0]", describe(new PropertiesNoveltySearchFactory().getSearchAlgorithm()));
        assertEquals("listeners=[MaxGenerationStoppingCondition, MaxTimeStoppingCondition, MutationTimeoutStoppingCondition, RelativeSuiteLengthBloatControl, ResourceController, ZeroFitnessStoppingCondition] stop=[MaxTimeStoppingCondition=60, MutationTimeoutStoppingCondition=3, ZeroFitnessStoppingCondition=0]", describe(new PropertiesMapElitesSearchFactory().getSearchAlgorithm()));
    }

    @Test
    void mutationCriterionOutsideOneBranch() throws Exception {
        Properties.CRITERION = new Criterion[]{Criterion.BRANCH, Criterion.MUTATION};
        assertEquals("listeners=[RelativeSuiteLengthBloatControl] stop=[MaxTimeStoppingCondition=60, ZeroFitnessStoppingCondition=0]", suite());
        assertEquals("listeners=[MaxGenerationStoppingCondition, MaxTimeStoppingCondition, RelativeSuiteLengthBloatControl, ResourceController, ZeroFitnessStoppingCondition] stop=[MaxTimeStoppingCondition=60, ZeroFitnessStoppingCondition=0]", novelty());
        Properties.STRATEGY = Properties.Strategy.MAP_ELITES;
        RuntimeException e = assertThrows(RuntimeException.class,
                () -> new PropertiesMapElitesSearchFactory().getSearchAlgorithm());
        assertEquals("Broken code :(", e.getMessage());
    }

    @Test
    void mapElitesRejectsLocalSearchCoverageRestore() {
        Properties.LOCAL_SEARCH_RESTORE_COVERAGE = true;
        RuntimeException e = assertThrows(RuntimeException.class,
                () -> new PropertiesMapElitesSearchFactory().getSearchAlgorithm());
        assertEquals("Broken code :(", e.getMessage());
    }

    @Test
    void localSearchRestoreCoverage() throws Exception {
        Properties.LOCAL_SEARCH_RESTORE_COVERAGE = true;
        Properties.STRATEGY = Properties.Strategy.EVOSUITE;
        Properties.TEST_FACTORY = Properties.TestFactory.RANDOM;
        Properties.ALGORITHM = Properties.Algorithm.MONOTONIC_GA;
        assertEquals("listeners=[BranchCoverageMap, MaxGenerationStoppingCondition, MaxTimeStoppingCondition, RelativeSuiteLengthBloatControl, ResourceController, ZeroFitnessStoppingCondition] stop=[MaxTimeStoppingCondition=60, ZeroFitnessStoppingCondition=0]", suite());
        assertEquals("listeners=[MaxGenerationStoppingCondition, MaxTimeStoppingCondition, RelativeTestLengthBloatControl, TestCaseRecycler, ZeroFitnessStoppingCondition] stop=[MaxTimeStoppingCondition=60, ZeroFitnessStoppingCondition=0]", test());
        assertEquals("listeners=[MaxGenerationStoppingCondition, MaxTimeStoppingCondition, RelativeSuiteLengthBloatControl, ResourceController, ZeroFitnessStoppingCondition] stop=[MaxTimeStoppingCondition=60, ZeroFitnessStoppingCondition=0]", novelty());
    }

    @Test
    void bloatControlAndRecyclingOff() throws Exception {
        Properties.CHECK_BEST_LENGTH = false;
        Properties.RECYCLE_CHROMOSOMES = false;
        assertEquals("listeners=[] stop=[MaxTimeStoppingCondition=60, ZeroFitnessStoppingCondition=0]", suite());
        assertEquals("listeners=[MaxGenerationStoppingCondition, MaxTimeStoppingCondition, ZeroFitnessStoppingCondition] stop=[MaxTimeStoppingCondition=60, ZeroFitnessStoppingCondition=0]", test());
        assertEquals("listeners=[MaxGenerationStoppingCondition, MaxTimeStoppingCondition, ResourceController, ZeroFitnessStoppingCondition] stop=[MaxTimeStoppingCondition=60, ZeroFitnessStoppingCondition=0]", novelty());
        assertEquals("listeners=[MaxGenerationStoppingCondition, MaxTimeStoppingCondition, ResourceController, ZeroFitnessStoppingCondition] stop=[MaxTimeStoppingCondition=60, ZeroFitnessStoppingCondition=0]", mapElites());
    }

    @Test
    void newStatisticsListener() throws Exception {
        Properties.NEW_STATISTICS = true;
        assertEquals("listeners=[RelativeSuiteLengthBloatControl, StatisticsListener] stop=[MaxTimeStoppingCondition=60, ZeroFitnessStoppingCondition=0]", suite());
        assertEquals("listeners=[MaxGenerationStoppingCondition, MaxTimeStoppingCondition, RelativeTestLengthBloatControl, StatisticsListener, TestCaseRecycler, ZeroFitnessStoppingCondition] stop=[MaxTimeStoppingCondition=60, ZeroFitnessStoppingCondition=0]", test());
        assertEquals("listeners=[MaxGenerationStoppingCondition, MaxTimeStoppingCondition, RelativeSuiteLengthBloatControl, ResourceController, StatisticsListener, ZeroFitnessStoppingCondition] stop=[MaxTimeStoppingCondition=60, ZeroFitnessStoppingCondition=0]", novelty());
        assertEquals("listeners=[MaxGenerationStoppingCondition, MaxTimeStoppingCondition, RelativeSuiteLengthBloatControl, ResourceController, StatisticsListener, ZeroFitnessStoppingCondition] stop=[MaxTimeStoppingCondition=60, ZeroFitnessStoppingCondition=0]", mapElites());
    }
}
