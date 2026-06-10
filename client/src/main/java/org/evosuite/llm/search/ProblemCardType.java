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
    UNREACHED_METHOD(
            "Reuse a working acquisition/setup prefix and append a direct call to the target method; "
                    + "focus on reaching it and skip assertions."),
    BRANCH_POLARITY_GAP(
            "Reuse a working prefix, then vary one input/state axis at a time to flip the target "
                    + "predicate outcome. Prefer small orthogonal variants; assertions are unnecessary."),
    STATE_DIVERSIFICATION_GAP(
            "Keep the working call sequence, then explore orthogonal regimes by changing exactly one of: "
                    + "inputs, receiver state, or dependency shape. Avoid near-duplicate variants; assertions "
                    + "are unnecessary."),
    EXCEPTION_BARRIER(
            "Avoid previously failing call patterns and try guarded preconditions "
                    + "or alternate call order."),
    CDG_BOTTLENECK(
            "Reuse a test that reaches the controlling predicate, then change the smallest relevant "
                    + "input or receiver-state condition needed to satisfy the required dependency outcome."),
    UNINSTANTIABLE_TYPE(
            "Try alternate object acquisition paths (different constructor args, "
                    + "factory/builder methods, or intermediate dependency creation), and aim to obtain "
                    + "one usable instance that can survive long enough for the next step."),
    STATE_SETUP_BARRIER(
            "Construct the object first, then drive a valid setup/lifecycle sequence "
                    + "before invoking target behavior."),
    INDIRECT_REACHABILITY_BARRIER(
            "Drive the outer entrypoint workflow or reuse any working outer workflow/setup prefix "
                    + "that gets close to the target type, then append the missing direct target invocation; "
                    + "assertions are unnecessary."),
    /**
     * The declaring type of one or more uncovered goals has no observed
     * acquisition, invocation, or construction activity in the current
     * population at all. The other type-family cards all require at least one
     * attempted constructor/factory/setup step; this card captures the case
     * where the type is genuinely untouched.
     */
    TYPE_NEVER_ATTEMPTED(
            "Construct an instance of this type via any visible constructor or factory; "
                    + "one test that simply reaches the type is sufficient — assertions are unnecessary.");

    private final String actionHint;

    ProblemCardType(String actionHint) {
        this.actionHint = actionHint == null ? "" : actionHint;
    }

    /**
     * Returns the suggested-action sentence rendered by {@link ProblemCardFormatter}
     * for this card type.
     */
    public String getActionHint() {
        return actionHint;
    }

    public ProblemCardFamily getDefaultFamily() {
        switch (this) {
            case UNINSTANTIABLE_TYPE:
            case STATE_SETUP_BARRIER:
            case INDIRECT_REACHABILITY_BARRIER:
            case TYPE_NEVER_ATTEMPTED:
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
