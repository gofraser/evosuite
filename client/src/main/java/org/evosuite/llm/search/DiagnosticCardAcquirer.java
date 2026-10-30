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
package org.evosuite.llm.search;

import org.evosuite.llm.prompt.GoalDescriptionMapper;
import org.evosuite.testcase.TestChromosome;
import org.evosuite.testcase.TestFitnessFunction;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Extracts diagnostic problem cards for a population and selects among them, keeping the
 * evidence of earlier snapshots. The callers decide the card limit, what to record about the
 * result and how to render the selected cards.
 */
final class DiagnosticCardAcquirer {

    private final GoalDescriptionMapper goalDescriptionMapper = new GoalDescriptionMapper();
    private final ProblemCardExtractor problemCardExtractor = new ProblemCardExtractor();
    private final DiagnosticCardSelector diagnosticCardSelector = new DiagnosticCardSelector();
    private final ExceptionBarrierTracker exceptionBarrierTracker = new ExceptionBarrierTracker();

    GoalDescriptionMapper goalDescriptionMapper() {
        return goalDescriptionMapper;
    }

    /** Forgets the evidence of earlier snapshots. */
    void resetHistory() {
        exceptionBarrierTracker.reset();
    }

    /**
     * Extracts the cards for the goals, adds the population's evidence to the history and selects
     * from the extracted cards.
     *
     * @param goals the goals still uncovered
     * @param population the current population
     * @param maxCards maximum number of cards to select
     * @param repeatedInjectionMemory memory of earlier attempts, may be null
     * @param suppressInFlightRepeats whether targets with an attempt in flight are suppressed
     * @return the extraction, and the selection unless nothing was extracted
     */
    Acquisition acquire(Collection<TestFitnessFunction> goals, List<TestChromosome> population,
                        int maxCards, RepeatedInjectionMemory repeatedInjectionMemory,
                        boolean suppressInFlightRepeats) {
        // The extractor counts the current population itself, so the historical stats must
        // be taken before this population enters the tracker's window.
        Map<String, AttemptStats> historicalStats = exceptionBarrierTracker.snapshot(goalMethodKeys(goals));
        ProblemCardExtractor.ExtractionResult extraction = problemCardExtractor.extractWithTelemetry(
                goals, population, Integer.MAX_VALUE, historicalStats);
        exceptionBarrierTracker.record(extraction.getMethodStats());
        if (extraction.getCards().isEmpty()) {
            return new Acquisition(extraction, null);
        }
        return new Acquisition(extraction, diagnosticCardSelector.select(
                extraction.getCards(), maxCards, repeatedInjectionMemory, suppressInFlightRepeats));
    }

    private Set<String> goalMethodKeys(Collection<TestFitnessFunction> goals) {
        if (goals == null || goals.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> keys = new LinkedHashSet<>();
        for (TestFitnessFunction goal : goals) {
            if (goal == null) {
                continue;
            }
            String key = goalDescriptionMapper.describeMethodOperation(goal).getExecutionKey();
            if (!key.isEmpty()) {
                keys.add(key);
            }
        }
        return keys;
    }

    /** The cards a population yielded and the cards selected among them. */
    static final class Acquisition {
        private final ProblemCardExtractor.ExtractionResult extraction;
        private final DiagnosticCardSelector.SelectionResult selection;

        private Acquisition(ProblemCardExtractor.ExtractionResult extraction,
                            DiagnosticCardSelector.SelectionResult selection) {
            this.extraction = extraction;
            this.selection = selection;
        }

        ProblemCardExtractor.ExtractionResult extraction() {
            return extraction;
        }

        /** Returns the selection, or null if the extraction yielded no card. */
        DiagnosticCardSelector.SelectionResult selection() {
            return selection;
        }
    }
}
