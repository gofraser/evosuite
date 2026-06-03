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
 * High-level blocker categories used by diagnostic stagnation prompts.
 */
public enum ProblemCardType {
    UNREACHED_METHOD,
    BRANCH_POLARITY_GAP,
    STATE_DIVERSIFICATION_GAP,
    EXCEPTION_BARRIER,
    CDG_BOTTLENECK,
    UNINSTANTIABLE_TYPE,
    STATE_SETUP_BARRIER,
    INDIRECT_REACHABILITY_BARRIER;

    public ProblemCardFamily getDefaultFamily() {
        switch (this) {
            case UNINSTANTIABLE_TYPE:
            case STATE_SETUP_BARRIER:
            case INDIRECT_REACHABILITY_BARRIER:
                return ProblemCardFamily.STRUCTURAL;
            case UNREACHED_METHOD:
            case EXCEPTION_BARRIER:
                return ProblemCardFamily.EXECUTION;
            case BRANCH_POLARITY_GAP:
            case STATE_DIVERSIFICATION_GAP:
            case CDG_BOTTLENECK:
            default:
                return ProblemCardFamily.LOCAL;
        }
    }
}
