/*
 * Copyright (C) 2010-2018 Gordon Fraser, Andrea Arcuri and EvoSuite
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
 * License along with EvoSuite. If not, see <http://www.gnu.org/licenses/>.
 */

package org.evosuite.testcase;

import org.evosuite.testcase.statements.*;


/**
 * The TestVisitor class provides an interface and a dispatcher for visiting different types of
 * statements and expressions in a test case.
 *
 * @author fraser
 */
public abstract class TestVisitor {

    /**
     * Visits a test case.
     *
     * @param test the {@link TestCase} object to visit.
     */
    public abstract void visitTestCase(TestCase test);

    /**
     * Visits a primitive statement.
     *
     * @param statement the {@link PrimitiveStatement} object to visit.
     */
    public abstract void visitPrimitiveStatement(PrimitiveStatement<?> statement);

    /**
     * Visits a field statement.
     *
     * @param statement the {@link FieldStatement} object to visit.
     */
    public abstract void visitFieldStatement(FieldStatement statement);

    /**
     * Visits a method statement.
     *
     * @param statement the {@link MethodStatement} object to visit.
     */
    public abstract void visitMethodStatement(MethodStatement statement);

    /**
     * Visits a constructor statement.
     *
     * @param statement the {@link ConstructorStatement} object to visit.
     */
    public abstract void visitConstructorStatement(ConstructorStatement statement);

    /**
     * Visits an array statement.
     *
     * @param statement the {@link ArrayStatement} object to visit.
     */
    public abstract void visitArrayStatement(ArrayStatement statement);

    /**
     * Visits an assignment statement.
     *
     * @param statement the {@link AssignmentStatement} object to visit.
     */
    public abstract void visitAssignmentStatement(AssignmentStatement statement);

    /**
     * Visits a null statement.
     *
     * @param statement the {@link NullStatement} object to visit.
     */
    public abstract void visitNullStatement(NullStatement statement);

    /**
     * Visits a primitive expression.
     *
     * @param primitiveExpression the {@link PrimitiveExpression} object to visit.
     */
    public abstract void visitPrimitiveExpression(PrimitiveExpression primitiveExpression);

    /**
     * Visits a functional mock statement.
     *
     * @param functionalMockStatement the {@link FunctionalMockStatement} object to visit.
     */
    public abstract void visitFunctionalMockStatement(FunctionalMockStatement functionalMockStatement);

    /**
     * Dispatches the call to the appropriate visit method based on the type of statement.
     *
     * @param statement the {@link Statement} object to visit.
     */
    public void visitStatement(Statement statement) {

        if (statement instanceof PrimitiveStatement<?>) {
            visitPrimitiveStatement((PrimitiveStatement<?>) statement);
        } else if (statement instanceof FieldStatement) {
            visitFieldStatement((FieldStatement) statement);
        } else if (statement instanceof ConstructorStatement) {
            visitConstructorStatement((ConstructorStatement) statement);
        } else if (statement instanceof MethodStatement) {
            visitMethodStatement((MethodStatement) statement);
        } else if (statement instanceof AssignmentStatement) {
            visitAssignmentStatement((AssignmentStatement) statement);
        } else if (statement instanceof ArrayStatement) {
            visitArrayStatement((ArrayStatement) statement);
        } else if (statement instanceof NullStatement) {
            visitNullStatement((NullStatement) statement);
        } else if (statement instanceof PrimitiveExpression) {
            visitPrimitiveExpression((PrimitiveExpression) statement);
        } else if (statement instanceof FunctionalMockStatement) {
            visitFunctionalMockStatement((FunctionalMockStatement) statement);
        } else {
            throw new RuntimeException("Unknown statement type: " + statement);
        }
    }
}

