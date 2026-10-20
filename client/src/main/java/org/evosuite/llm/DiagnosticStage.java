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
package org.evosuite.llm;

import org.evosuite.llm.search.ProblemCardType;
import org.evosuite.statistics.RuntimeVariable;

/**
 * A point in the diagnostic-card pipeline that is counted in total and per {@link ProblemCardType}.
 * Each stage owns the counters and the column prefix of its per-type variables, such as
 * {@code LLM_Diagnostic_Candidates_Admitted_} followed by the type in camel case. A new card type
 * needs its columns in {@link RuntimeVariable}; the stages find them by name.
 */
enum DiagnosticStage {
    /** Cards the extractors produced, before selection or truncation. */
    EXTRACTED(RuntimeVariable.LLM_Diagnostic_Cards_Extracted, "LLM_Diagnostic_Cards_Extracted_"),
    /** Cards that went into a stagnation prompt. */
    SELECTED(RuntimeVariable.LLM_Diagnostic_Cards_Selected, "LLM_Diagnostic_Cards_"),
    /** Candidate tests that were published for the cards. */
    PUBLISHED(RuntimeVariable.LLM_Diagnostic_Candidates_Published, "LLM_Diagnostic_Candidates_Published_"),
    /** Candidate tests that the search admitted. */
    ADMITTED(RuntimeVariable.LLM_Diagnostic_Candidates_Admitted, "LLM_Diagnostic_Candidates_Admitted_"),
    /** Candidate tests that survived selection. */
    SURVIVED(RuntimeVariable.LLM_Diagnostic_Candidates_Survived, "LLM_Diagnostic_Candidates_Survived_"),
    /** Uncovered goals gained, attributed to a single card type. */
    COVERAGE_GAIN(RuntimeVariable.LLM_Diagnostic_Coverage_Gains, "LLM_Diagnostic_Coverage_Gains_");

    private final ColumnCounters<ProblemCardType> counters;

    DiagnosticStage(RuntimeVariable total, String columnPrefix) {
        this.counters = new ColumnCounters<>(ProblemCardType.class, total, columnPrefix);
    }

    ColumnCounters<ProblemCardType> counters() {
        return counters;
    }
}
