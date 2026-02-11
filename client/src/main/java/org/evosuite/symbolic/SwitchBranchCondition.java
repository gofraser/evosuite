/**
 * Copyright (C) 2010-2018 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 *
 * <p>This file is part of EvoSuite.
 *
 * <p>EvoSuite is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published
 * by the Free Software Foundation, either version 3.0 of the License, or
 * (at your option) any later version.
 *
 * <p>EvoSuite is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser Public License for more details.
 *
 * <p>You should have received a copy of the GNU Lesser General Public
 * License along with EvoSuite. If not, see http://www.gnu.org/licenses/.
 */
package org.evosuite.symbolic;

import org.evosuite.symbolic.expr.Constraint;

import java.util.List;

/**
 * Represents a branch condition created from the execution of a
 * <code>switch</code> instruction.
 *
 * @author jgaleotti
 */
public final class SwitchBranchCondition extends BranchCondition {

    /**
     * Indicates if the current <code>switch</code> branch condition is the default
     * goal or not (i.e. no specific goal).
     */
    private final boolean isDefaultGoal;

    /**
     * If the current switch branch condition is *not* a default goal, this field
     * contains the goal value.
     */
    private final int goalValue;

    /**
     * Constructor for a default switch branch condition.
     *
     * @param className a {@link java.lang.String} object.
     * @param methodName a {@link java.lang.String} object.
     * @param instructionIndex a int.
     * @param constraint a {@link org.evosuite.symbolic.expr.Constraint} object.
     * @param supportingConstraints a {@link java.util.List} object.
     */
    public SwitchBranchCondition(String className, String methodName, int instructionIndex, Constraint<?> constraint,
                                 List<Constraint<?>> supportingConstraints) {
        super(className, methodName, instructionIndex, constraint, supportingConstraints);
        this.goalValue = 0;
        this.isDefaultGoal = true;
    }

    /**
     * Constructor for a non-default switch branch condition.
     *
     * @param className a {@link java.lang.String} object.
     * @param methodName a {@link java.lang.String} object.
     * @param instructionIndex a int.
     * @param constraint a {@link org.evosuite.symbolic.expr.Constraint} object.
     * @param supportingConstraints a {@link java.util.List} object.
     * @param goalValue a int.
     */
    public SwitchBranchCondition(String className, String methodName, int instructionIndex, Constraint<?> constraint,
                                 List<Constraint<?>> supportingConstraints, int goalValue) {
        super(className, methodName, instructionIndex, constraint, supportingConstraints);
        this.goalValue = goalValue;
        this.isDefaultGoal = false;
    }

    /**
     * Indicates if the current switch branch condition is the default branch
     * condition.
     *
     * @return a boolean.
     */
    public boolean isDefaultGoal() {
        return isDefaultGoal;
    }

    /**
     * Returns the goal of the switch branch condition. The switch branch
     * condition needs to be a non-default switch branch condition.
     *
     * @return a int.
     */
    public int getGoalValue() throws IllegalStateException {
        if (!isDefaultGoal()) {
            return goalValue;
        } else {
            throw new IllegalStateException("cannot request goal to a default goal branch condition");
        }
    }

}
