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
package org.evosuite.testcase.statements;

import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.execution.CodeUnderTestException;
import org.evosuite.testcase.execution.Scope;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.testcase.variable.VariableReferenceImpl;
import org.evosuite.utils.generic.GenericAccessibleObject;

import java.io.PrintStream;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

// TODO-JRO Implement methods of PrimitiveExpression as needed

/**
 * Represents a primitive expression of the form {@code lhs op rhs} where {@code op} is a binary
 * operator, and {@code lhs} and {@code rhs} are the left-hand side and right-hand side of {@code
 * op}, respectively.
 */
public class PrimitiveExpression extends AbstractStatement {

    public enum Operator {
        TIMES("*"), //
        DIVIDE("/"), //
        REMAINDER("%"), //
        PLUS("+"), //
        MINUS("-"), //
        LEFT_SHIFT("<<"), //
        RIGHT_SHIFT_SIGNED(">>"), //
        RIGHT_SHIFT_UNSIGNED(">>>"), //
        LESS("<"), //
        GREATER(">"), //
        LESS_EQUALS("<="), //
        GREATER_EQUALS(">="), //
        EQUALS("=="), //
        NOT_EQUALS("!="), //
        XOR("^"), //
        AND("&"), //
        OR("|"), //
        CONDITIONAL_AND("&&"), //
        CONDITIONAL_OR("||");

        /**
         * Returns the operator corresponding to the given code.
         *
         * @param code the operator code
         * @return the operator
         */
        public static Operator toOperator(String code) {
            for (Operator operator : values()) {
                if (operator.code.equals(code)) {
                    return operator;
                }
            }
            throw new RuntimeException("No operator for " + code);
        }

        private final String code;

        Operator(String code) {
            this.code = code;
        }

        public String toCode() {
            return code;
        }
    }

    private static final long serialVersionUID = 1L;

    private VariableReference leftOperand;
    private final Operator operator;
    private VariableReference rightOperand;

    /**
     * <p>
     * Constructor for PrimitiveExpression.
     * </p>
     *
     * @param testCase     a {@link org.evosuite.testcase.TestCase} object.
     * @param reference    a {@link org.evosuite.testcase.variable.VariableReference} object.
     * @param leftOperand  a {@link org.evosuite.testcase.variable.VariableReference} object.
     * @param operator     a {@link org.evosuite.testcase.statements.PrimitiveExpression.Operator}
     *                     object.
     * @param rightOperand a {@link org.evosuite.testcase.variable.VariableReference} object.
     */
    public PrimitiveExpression(TestCase testCase, VariableReference reference,
                               VariableReference leftOperand, Operator operator,
                               VariableReference rightOperand) {
        super(testCase, reference);
        this.leftOperand = leftOperand;
        this.operator = operator;
        this.rightOperand = rightOperand;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Statement copy(TestCase newTestCase, int offset) {
        VariableReference newRetVal = new VariableReferenceImpl(newTestCase,
                retval.getType());
        VariableReference newLeftOperand = newTestCase.getStatement(leftOperand.getStPosition()).getReturnValue();
        VariableReference newRightOperand = newTestCase.getStatement(rightOperand.getStPosition()).getReturnValue();
        return new PrimitiveExpression(newTestCase, newRetVal, newLeftOperand, operator,
                newRightOperand);
        //        return new PrimitiveExpression(newTestCase, retval, leftOperand, operator, rightOperand);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Throwable execute(Scope scope, PrintStream out)
            throws IllegalArgumentException {
        try {
            Object o1 = leftOperand.getObject(scope);
            Object o2 = rightOperand.getObject(scope);

            // Comparison operators produce boolean results
            switch (operator) {
                case EQUALS:
                    scope.setObject(retval, Objects.equals(o1, o2));
                    return null;
                case NOT_EQUALS:
                    scope.setObject(retval, !Objects.equals(o1, o2));
                    return null;
                default:
                    break;
            }

            // Remaining operators require numeric operands
            if (!(o1 instanceof Number) || !(o2 instanceof Number)) {
                // String concatenation with PLUS
                if (operator == Operator.PLUS) {
                    scope.setObject(retval, String.valueOf(o1) + String.valueOf(o2));
                    return null;
                }
                throw new UnsupportedOperationException(
                        "Non-numeric operands for operator " + operator);
            }

            Number n1 = (Number) o1;
            Number n2 = (Number) o2;

            // Determine widest type for arithmetic
            boolean isDouble = (o1 instanceof Double || o2 instanceof Double);
            boolean isFloat = (o1 instanceof Float || o2 instanceof Float);
            boolean isLong = (o1 instanceof Long || o2 instanceof Long);

            switch (operator) {
                // Arithmetic operators
                case PLUS:
                    if (isDouble) scope.setObject(retval, n1.doubleValue() + n2.doubleValue());
                    else if (isFloat) scope.setObject(retval, n1.floatValue() + n2.floatValue());
                    else if (isLong) scope.setObject(retval, n1.longValue() + n2.longValue());
                    else scope.setObject(retval, n1.intValue() + n2.intValue());
                    break;
                case MINUS:
                    if (isDouble) scope.setObject(retval, n1.doubleValue() - n2.doubleValue());
                    else if (isFloat) scope.setObject(retval, n1.floatValue() - n2.floatValue());
                    else if (isLong) scope.setObject(retval, n1.longValue() - n2.longValue());
                    else scope.setObject(retval, n1.intValue() - n2.intValue());
                    break;
                case TIMES:
                    if (isDouble) scope.setObject(retval, n1.doubleValue() * n2.doubleValue());
                    else if (isFloat) scope.setObject(retval, n1.floatValue() * n2.floatValue());
                    else if (isLong) scope.setObject(retval, n1.longValue() * n2.longValue());
                    else scope.setObject(retval, n1.intValue() * n2.intValue());
                    break;
                case DIVIDE:
                    if (isDouble) scope.setObject(retval, n1.doubleValue() / n2.doubleValue());
                    else if (isFloat) scope.setObject(retval, n1.floatValue() / n2.floatValue());
                    else if (isLong) scope.setObject(retval, n1.longValue() / n2.longValue());
                    else scope.setObject(retval, n1.intValue() / n2.intValue());
                    break;
                case REMAINDER:
                    if (isDouble) scope.setObject(retval, n1.doubleValue() % n2.doubleValue());
                    else if (isFloat) scope.setObject(retval, n1.floatValue() % n2.floatValue());
                    else if (isLong) scope.setObject(retval, n1.longValue() % n2.longValue());
                    else scope.setObject(retval, n1.intValue() % n2.intValue());
                    break;

                // Comparison operators (produce boolean)
                case LESS:
                    scope.setObject(retval, Double.compare(n1.doubleValue(), n2.doubleValue()) < 0);
                    break;
                case GREATER:
                    scope.setObject(retval, Double.compare(n1.doubleValue(), n2.doubleValue()) > 0);
                    break;
                case LESS_EQUALS:
                    scope.setObject(retval, Double.compare(n1.doubleValue(), n2.doubleValue()) <= 0);
                    break;
                case GREATER_EQUALS:
                    scope.setObject(retval, Double.compare(n1.doubleValue(), n2.doubleValue()) >= 0);
                    break;

                // Bitwise/shift operators (integer types only)
                case LEFT_SHIFT:
                    if (isLong) scope.setObject(retval, n1.longValue() << n2.intValue());
                    else scope.setObject(retval, n1.intValue() << n2.intValue());
                    break;
                case RIGHT_SHIFT_SIGNED:
                    if (isLong) scope.setObject(retval, n1.longValue() >> n2.intValue());
                    else scope.setObject(retval, n1.intValue() >> n2.intValue());
                    break;
                case RIGHT_SHIFT_UNSIGNED:
                    if (isLong) scope.setObject(retval, n1.longValue() >>> n2.intValue());
                    else scope.setObject(retval, n1.intValue() >>> n2.intValue());
                    break;
                case AND:
                    if (isLong) scope.setObject(retval, n1.longValue() & n2.longValue());
                    else scope.setObject(retval, n1.intValue() & n2.intValue());
                    break;
                case OR:
                    if (isLong) scope.setObject(retval, n1.longValue() | n2.longValue());
                    else scope.setObject(retval, n1.intValue() | n2.intValue());
                    break;
                case XOR:
                    if (isLong) scope.setObject(retval, n1.longValue() ^ n2.longValue());
                    else scope.setObject(retval, n1.intValue() ^ n2.intValue());
                    break;

                // Logical operators (operands should be boolean, but we handle Number fallback)
                case CONDITIONAL_AND:
                    scope.setObject(retval, n1.intValue() != 0 && n2.intValue() != 0);
                    break;
                case CONDITIONAL_OR:
                    scope.setObject(retval, n1.intValue() != 0 || n2.intValue() != 0);
                    break;

                default:
                    throw new UnsupportedOperationException(
                            "Unsupported operator: " + operator);
            }
        } catch (CodeUnderTestException e) {
            return e;
        }
        return null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public GenericAccessibleObject<?> getAccessibleObject() {
        return null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getCode() {
        String code = ((Class<?>) retval.getType()).getSimpleName() + " "
                + retval.getName() + " = " + leftOperand.getName() + " "
                + operator.toCode() + " " + rightOperand.getName() + ";";
        return code;
    }

    /**
     * <p>
     * Getter for the field <code>leftOperand</code>.
     * </p>
     *
     * @return a {@link org.evosuite.testcase.variable.VariableReference} object.
     */
    public VariableReference getLeftOperand() {
        return leftOperand;
    }

    /**
     * Getter for the field <code>operator</code>.
     *
     * @return a {@link org.evosuite.testcase.statements.PrimitiveExpression.Operator}
     *     object.
     */
    public Operator getOperator() {
        return operator;
    }

    /**
     * Getter for the field <code>rightOperand</code>.
     *
     * @return a {@link org.evosuite.testcase.variable.VariableReference} object.
     */
    public VariableReference getRightOperand() {
        return rightOperand;
    }



    /**
     * {@inheritDoc}
     */
    @Override
    public Set<VariableReference> getVariableReferences() {
        Set<VariableReference> result = new LinkedHashSet<>();
        result.add(retval);
        result.add(leftOperand);
        result.add(rightOperand);
        result.addAll(getAssertionReferences());
        return result;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isAssignmentStatement() {
        return false;
    }

    @Override
    public void replace(VariableReference oldVar, VariableReference newVar) {
        if (retval.equals(oldVar)) {
            retval = newVar;
        }
        if (leftOperand.equals(oldVar)) {
            leftOperand = newVar;
        }
        if (rightOperand.equals(oldVar)) {
            rightOperand = newVar;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean same(Statement s) {
        if (this == s) {
            return true;
        }
        if (s == null || getClass() != s.getClass()) {
            return false;
        }

        PrimitiveExpression ps = (PrimitiveExpression) s;

        return operator == ps.operator && leftOperand.same(ps.leftOperand)
                && rightOperand.same(ps.rightOperand) && retval.same(ps.retval);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        PrimitiveExpression that = (PrimitiveExpression) o;
        return operator == that.operator &&
                Objects.equals(leftOperand, that.leftOperand) &&
                Objects.equals(rightOperand, that.rightOperand) &&
                Objects.equals(retval, that.retval);
    }

    @Override
    public int hashCode() {
        return Objects.hash(operator, leftOperand, rightOperand, retval);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        return getCode();
    }
}
