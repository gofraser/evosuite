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

/**
 * Early extractor-side candidate metrics used to explain where blocker-card
 * pipelines stop before a card is emitted.
 */
public enum ExtractorCandidateMetric {
    UPSTREAM_EXCEPTION_REPEATED_SOURCES,
    UPSTREAM_EXCEPTION_BLOCKED_GOAL_METHODS,
    EXCEPTION_BARRIER_METHOD_CANDIDATES,
    EXCEPTION_BARRIER_CONTEXT_CANDIDATES,
    EXCEPTION_BARRIER_UPSTREAM_CANDIDATES,
    EXCEPTION_BARRIER_SUPPRESSED_INSUFFICIENT_ATTEMPTS,
    EXCEPTION_BARRIER_SUPPRESSED_LOW_FAILURE_RATE,
    TYPE_BARRIER_SIGNALS_WITH_CONSTRUCTION_FAILURE,
    TYPE_BARRIER_SIGNALS_WITH_SETUP_FAILURE,
    UNINSTANTIABLE_TYPE_CANDIDATES,
    UNINSTANTIABLE_TYPE_SUPPRESSED_INSUFFICIENT_ATTEMPTS,
    UNINSTANTIABLE_TYPE_SUPPRESSED_LOW_FAILURE_RATE,
    STATE_SETUP_BARRIER_CANDIDATES,
    STATE_SETUP_BARRIER_SUPPRESSED_NO_SUCCESSFUL_ACQUISITION,
    STATE_SETUP_BARRIER_SUPPRESSED_INSUFFICIENT_ATTEMPTS,
    STATE_SETUP_BARRIER_SUPPRESSED_INCONSISTENT_FAILING_STEP,
    MOCK_NEEDED_DEPENDENCY_CANDIDATES,
    MOCK_NEEDED_DEPENDENCY_SUPPRESSED_MATERIALIZED,
    ENVIRONMENT_BARRIER_CANDIDATES
}
