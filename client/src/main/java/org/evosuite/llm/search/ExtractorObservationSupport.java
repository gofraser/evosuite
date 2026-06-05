/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 *
 * This file is part of EvoSuite.
 *
 * EvoSuite is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
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

import org.evosuite.coverage.branch.Branch;
import org.evosuite.coverage.branch.BranchCoverageGoal;
import org.evosuite.coverage.branch.BranchCoverageTestFitness;
import org.evosuite.coverage.branch.OnlyBranchCoverageTestFitness;
import org.evosuite.llm.prompt.GoalDescriptionMapper;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestFitnessFunction;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.statements.ConstructorStatement;
import org.evosuite.testcase.statements.MethodStatement;
import org.evosuite.testcase.statements.Statement;

import java.util.Collections;
import java.util.Set;

final class ExtractorObservationSupport {

    private static final GoalDescriptionMapper GOAL_DESCRIPTION_MAPPER = new GoalDescriptionMapper();

    private ExtractorObservationSupport() {
        // Utility class.
    }

    static int firstThrownPosition(ExecutionResult result) {
        try {
            Integer pos = result.getFirstPositionOfThrownException();
            return pos == null ? -1 : pos;
        } catch (RuntimeException e) {
            return -1;
        }
    }

    static int safeTestSize(TestCase test) {
        try {
            return test == null ? 0 : test.size();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    static Statement statementAt(TestCase test, int position) {
        if (test == null || position < 0) {
            return null;
        }
        try {
            if (!test.hasStatement(position)) {
                return null;
            }
            return test.getStatement(position);
        } catch (RuntimeException e) {
            return null;
        }
    }

    static String exceptionKey(ExecutionResult result) {
        try {
            Integer pos = result.getFirstPositionOfThrownException();
            if (pos == null) {
                return "";
            }
            Throwable thrown = result.getExceptionThrownAtPosition(pos);
            Throwable root = rootCause(thrown);
            return root == null ? "" : root.getClass().getSimpleName();
        } catch (RuntimeException e) {
            return "";
        }
    }

    static Throwable rootCause(Throwable throwable) {
        if (throwable == null) {
            return null;
        }
        Throwable current = throwable;
        int safety = 0;
        while (current.getCause() != null && current.getCause() != current && safety++ < 32) {
            current = current.getCause();
        }
        return current;
    }

    static String directMethodKey(MethodStatement statement) {
        return GOAL_DESCRIPTION_MAPPER.describeMethodOperation(statement).getExecutionKey();
    }

    static String coveredMethodKey(String coveredMethod) {
        return GOAL_DESCRIPTION_MAPPER.describeQualifiedMethodOperation(coveredMethod).getExecutionKey();
    }

    static String declaringTypeForMethod(MethodStatement statement) {
        if (statement == null || statement.getMethod() == null || statement.getMethod().getMethod() == null) {
            return "";
        }
        Class<?> declaringClass = statement.getMethod().getMethod().getDeclaringClass();
        return declaringClass == null ? "" : declaringClass.getName();
    }

    static String thrownTypeForMethod(MethodStatement statement) {
        if (statement == null || statement.getMethod() == null || statement.getMethod().getMethod() == null) {
            return "";
        }
        if (isFactoryMethod(statement)) {
            return statement.getMethod().getMethod().getDeclaringClass().getName();
        }
        if (statement.getCallee() != null && statement.getCallee().getVariableClass() != null) {
            return statement.getCallee().getVariableClass().getName();
        }
        Class<?> declaringClass = statement.getMethod().getMethod().getDeclaringClass();
        return declaringClass == null ? "" : declaringClass.getName();
    }

    static String constructorType(ConstructorStatement statement) {
        if (statement == null || statement.getConstructor() == null
                || statement.getConstructor().getDeclaringClass() == null) {
            return "";
        }
        return statement.getConstructor().getDeclaringClass().getName();
    }

    static boolean isFactoryMethod(MethodStatement statement) {
        if (statement == null || statement.getMethod() == null || statement.getMethod().getMethod() == null) {
            return false;
        }
        if (!statement.getMethod().isStatic()) {
            return false;
        }
        Class<?> declaring = statement.getMethod().getMethod().getDeclaringClass();
        Class<?> returned = statement.getMethod().getMethod().getReturnType();
        return declaring != null && returned != null && declaring.isAssignableFrom(returned);
    }

    static String setupMethodKey(MethodStatement statement) {
        return GOAL_DESCRIPTION_MAPPER.describeMethodOperation(statement).getExecutionKey();
    }

    static String statementDebugKind(Statement statement) {
        return statement == null ? "null" : statement.getClass().getSimpleName();
    }

    static String statementDebugLabel(Statement statement) {
        if (statement instanceof MethodStatement) {
            return GOAL_DESCRIPTION_MAPPER.describeMethodOperation((MethodStatement) statement).getDisplayLabel();
        }
        if (statement instanceof ConstructorStatement) {
            return GOAL_DESCRIPTION_MAPPER.describeConstructorOperation((ConstructorStatement) statement)
                    .getDisplayLabel();
        }
        return statementDebugKind(statement);
    }

    static BranchCoverageGoal branchCoverageGoalFor(TestFitnessFunction goal) {
        if (goal instanceof BranchCoverageTestFitness) {
            return ((BranchCoverageTestFitness) goal).getBranchGoal();
        }
        if (goal instanceof OnlyBranchCoverageTestFitness) {
            return ((OnlyBranchCoverageTestFitness) goal).getBranchGoal();
        }
        return null;
    }

    static BranchGoalSignal branchSignalForGoal(TestFitnessFunction goal) {
        BranchCoverageGoal branchGoal = branchCoverageGoalFor(goal);
        return fromBranchGoal(branchGoal, goal);
    }

    static BranchGoalSignal fromBranchGoal(BranchCoverageGoal branchGoal, TestFitnessFunction goal) {
        if (branchGoal == null) {
            return null;
        }
        Branch branch = branchGoal.getBranch();
        if (branch == null) {
            return null;
        }
        GoalDescriptionMapper.BranchTarget branchTarget = GOAL_DESCRIPTION_MAPPER.describeBranchTarget(branchGoal);
        if (branchTarget.isSwitchCaseBranch() && branchTarget.isDefaultCase()) {
            return null;
        }
        if (isSyntheticCompilerMethod(branchTarget.getTarget().getBaseMethodName())) {
            return null;
        }
        String methodKey = qualifiedMethodKey(branchTarget.getTarget());
        if (methodKey.isEmpty()) {
            return null;
        }
        return new BranchGoalSignal(methodKey,
                branch.getActualBranchId(),
                branchGoal.getValue(),
                branchTarget,
                goal);
    }

    static String qualifiedMethodKey(GoalDescriptionMapper.GoalTarget target) {
        if (target == null) {
            return "";
        }
        return target.getExecutionKey();
    }

    static Set<Integer> safeSet(Set<Integer> input) {
        return input == null ? Collections.emptySet() : input;
    }

    static boolean isSyntheticCompilerMethod(String baseMethodName) {
        if (baseMethodName == null || baseMethodName.isEmpty()) {
            return false;
        }
        return baseMethodName.startsWith("access$")
                || baseMethodName.startsWith("lambda$")
                || baseMethodName.startsWith("$deserializeLambda$")
                || baseMethodName.startsWith("$SWITCH_TABLE$");
    }

    static String outerTypeName(String typeName) {
        if (typeName == null || typeName.isEmpty()) {
            return "";
        }
        int idx = typeName.indexOf('$');
        if (idx <= 0) {
            return typeName;
        }
        return typeName.substring(0, idx);
    }
}
