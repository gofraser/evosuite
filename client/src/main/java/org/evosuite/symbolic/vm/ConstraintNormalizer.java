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
package org.evosuite.symbolic.vm;

import org.evosuite.symbolic.expr.Comparator;
import org.evosuite.symbolic.expr.Constraint;
import org.evosuite.symbolic.expr.Expression;
import org.evosuite.symbolic.expr.bv.IntegerComparison;
import org.evosuite.symbolic.expr.bv.IntegerConstant;
import org.evosuite.symbolic.expr.bv.RealComparison;
import org.evosuite.symbolic.expr.bv.StringComparison;
import org.evosuite.symbolic.expr.constraint.IntegerConstraint;
import org.evosuite.symbolic.expr.constraint.RealConstraint;
import org.evosuite.symbolic.expr.constraint.StringConstraint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Transforms an IntegerConstraint into its corresponding StringConstriant,
 * RealConstraint or IntegerConstraint.
 *
 * @author galeotti
 */
public final class ConstraintNormalizer {

    static Logger log = LoggerFactory.getLogger(ConstraintNormalizer.class);

    /**
     * Transforms an IntegerConstraint into a corresponding StringConstraint,
     * RealConstraint or IntegerConstraint.
     *
     * @param c a constraint to be normalized
     * @return the normalized constraint
     */
    public static Constraint<?> normalize(IntegerConstraint c) {

        Expression<?> left = c.getLeftOperand();
        Expression<?> right = c.getRightOperand();
        if (left instanceof StringComparison
                || right instanceof StringComparison) {
            return createStringConstraint(c);
        } else if (left instanceof RealComparison
                || right instanceof RealComparison) {
            return createRealConstraint(c);
        } else if (left instanceof IntegerComparison
                || right instanceof IntegerComparison) {
            return normalizeIntegerConstriant(c);
        }
        // return without normalization
        log.debug("Un-normalized constraint: " + c);
        return c;
    }

    private static Constraint<?> createStringConstraint(IntegerConstraint c) {

        if (c.getLeftOperand() instanceof StringComparison) {
            StringComparison stringComparison = (StringComparison) c
                    .getLeftOperand();
            @SuppressWarnings("unchecked")
            Expression<Long> numberExpr = (Expression<Long>) c
                    .getRightOperand();
            IntegerConstant constant = new IntegerConstant(
                    numberExpr.getConcreteValue());
            return new StringConstraint(stringComparison, c.getComparator(),
                    constant);
        } else {
            assert c.getRightOperand() instanceof StringComparison;
            StringComparison stringComparison = (StringComparison) c
                    .getRightOperand();
            @SuppressWarnings("unchecked")
            Expression<Long> numberExpr = (Expression<Long>) c
                    .getLeftOperand();
            IntegerConstant constant = new IntegerConstant(
                    numberExpr.getConcreteValue());
            return new StringConstraint(stringComparison, c.getComparator(),
                    constant);
        }
    }

    private static Constraint<?> createRealConstraint(IntegerConstraint c) {

        if (c.getLeftOperand() instanceof RealComparison) {

            RealComparison cmp = (RealComparison) c.getLeftOperand();
            int value = ((Number) c.getRightOperand().getConcreteValue())
                    .intValue();
            Comparator op = c.getComparator();

            Expression<Double> cmpLeft = cmp.getLeftOperant();
            Expression<Double> cmpRight = cmp.getRightOperant();
            return createRealConstraint(cmpLeft, op, cmpRight, value);

        } else {

            assert (c.getRightOperand() instanceof RealComparison);
            RealComparison cmp = (RealComparison) c.getRightOperand();

            Comparator op = c.getComparator();
            Comparator swapOp = op.swap();
            int value = ((Number) c.getLeftOperand().getConcreteValue())
                    .intValue();
            int swapValue = -value;
            Expression<Double> cmpLeft = cmp.getLeftOperant();
            Expression<Double> cmpRight = cmp.getRightOperant();

            return createRealConstraint(cmpLeft, swapOp, cmpRight,
                    swapValue);

        }

    }

    private static RealConstraint createRealConstraint(
            Expression<Double> cmpLeft, Comparator op,
            Expression<Double> cmpRight, int value) {
        switch (op) {
            case EQ:
                if (value < 0) {
                    return (new RealConstraint(cmpLeft, Comparator.LT, cmpRight));
                } else if (value == 0) {
                    return (new RealConstraint(cmpLeft, Comparator.EQ, cmpRight));
                } else {
                    return (new RealConstraint(cmpLeft, Comparator.GT, cmpRight));
                }
            case NE:
                if (value < 0) {
                    return (new RealConstraint(cmpLeft, Comparator.GE, cmpRight));
                } else if (value == 0) {
                    return (new RealConstraint(cmpLeft, Comparator.NE, cmpRight));
                } else {
                    return (new RealConstraint(cmpLeft, Comparator.LE, cmpRight));
                }
            case LE:
                if (value < 0) {
                    return (new RealConstraint(cmpLeft, Comparator.LT, cmpRight));
                } else if (value == 0) {
                    return (new RealConstraint(cmpLeft, Comparator.LE, cmpRight));
                } else {
                    throw new RuntimeException("Unexpected Constraint");
                }
            case LT:
                if (value < 0) {
                    throw new RuntimeException("Unexpected Constraint");
                } else if (value == 0) {
                    return (new RealConstraint(cmpLeft, Comparator.LT, cmpRight));
                } else {
                    return (new RealConstraint(cmpLeft, Comparator.LE, cmpRight));
                }
            case GE:
                if (value < 0) {
                    throw new RuntimeException("Unexpected Constraint");
                } else if (value == 0) {
                    return (new RealConstraint(cmpLeft, Comparator.GE, cmpRight));
                } else {
                    return (new RealConstraint(cmpLeft, Comparator.GT, cmpRight));
                }
            case GT:
                if (value < 0) {
                    return (new RealConstraint(cmpLeft, Comparator.GE, cmpRight));
                } else if (value == 0) {
                    return (new RealConstraint(cmpLeft, Comparator.GT, cmpRight));
                } else {
                    throw new RuntimeException("Unexpected Constraint");
                }
            default:
                throw new IllegalArgumentException("Unknown operator : " + op);
        }
    }

    private static Constraint<?> normalizeIntegerConstriant(IntegerConstraint c) {

        if (c.getLeftOperand() instanceof IntegerComparison) {
            IntegerComparison cmp = (IntegerComparison) c.getLeftOperand();
            int value = ((Number) c.getRightOperand().getConcreteValue())
                    .intValue();
            Comparator op = c.getComparator();
            Expression<Long> cmpLeft = cmp.getLeftOperant();
            Expression<Long> cmpRight = cmp.getRightOperant();
            return createIntegerConstraint(cmpLeft, op, cmpRight, value);

        } else {
            assert (c.getRightOperand() instanceof IntegerComparison);

            IntegerComparison cmp = (IntegerComparison) c.getRightOperand();
            int value = ((Number) c.getLeftOperand().getConcreteValue())
                    .intValue();
            Comparator op = c.getComparator();
            Expression<Long> cmpLeft = cmp.getLeftOperant();
            Expression<Long> cmpRight = cmp.getRightOperant();
            Comparator swapOp = op.swap();
            int swapValue = -value;
            return createIntegerConstraint(cmpLeft, swapOp, cmpRight,
                    swapValue);

        }
    }

    private static IntegerConstraint createIntegerConstraint(
            Expression<Long> cmpLeft, Comparator op,
            Expression<Long> cmpRight, int value) {
        switch (op) {
            case EQ:
                if (value < 0) {
                    return (new IntegerConstraint(cmpLeft, Comparator.LT,
                            cmpRight));
                } else if (value == 0) {
                    return (new IntegerConstraint(cmpLeft, Comparator.EQ,
                            cmpRight));
                } else {
                    return (new IntegerConstraint(cmpLeft, Comparator.GT,
                            cmpRight));
                }
            case NE:
                if (value < 0) {
                    return (new IntegerConstraint(cmpLeft, Comparator.GE,
                            cmpRight));
                } else if (value == 0) {
                    return (new IntegerConstraint(cmpLeft, Comparator.NE,
                            cmpRight));
                } else {
                    return (new IntegerConstraint(cmpLeft, Comparator.LE,
                            cmpRight));
                }
            case LE:
                if (value < 0) {
                    return (new IntegerConstraint(cmpLeft, Comparator.LT,
                            cmpRight));
                } else if (value == 0) {
                    return (new IntegerConstraint(cmpLeft, Comparator.LE,
                            cmpRight));
                } else {
                    throw new RuntimeException("Unexpected Constraint");
                }
            case LT:
                if (value < 0) {
                    throw new RuntimeException("Unexpected Constraint");
                } else if (value == 0) {
                    return (new IntegerConstraint(cmpLeft, Comparator.LT,
                            cmpRight));
                } else {
                    return (new IntegerConstraint(cmpLeft, Comparator.LE,
                            cmpRight));
                }
            case GE:
                if (value < 0) {
                    throw new RuntimeException("Unexpected Constraint");
                } else if (value == 0) {
                    return (new IntegerConstraint(cmpLeft, Comparator.GE,
                            cmpRight));
                } else {
                    return (new IntegerConstraint(cmpLeft, Comparator.GT,
                            cmpRight));
                }
            case GT:
                if (value < 0) {
                    return (new IntegerConstraint(cmpLeft, Comparator.GE,
                            cmpRight));
                } else if (value == 0) {
                    return (new IntegerConstraint(cmpLeft, Comparator.GT,
                            cmpRight));
                } else {
                    throw new RuntimeException("Unexpected Constraint");
                }
            default:
                throw new IllegalStateException("Unknown operator " + op);
        }
    }

}
