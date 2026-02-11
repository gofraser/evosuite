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
package org.evosuite.symbolic.solver.cvc4;

import dk.brics.automaton.RegExp;
import org.evosuite.symbolic.expr.Operator;
import org.evosuite.symbolic.expr.bv.IntegerBinaryExpression;
import org.evosuite.symbolic.expr.bv.StringBinaryComparison;
import org.evosuite.symbolic.expr.fp.RealBinaryExpression;
import org.evosuite.symbolic.solver.SmtExprBuilder;
import org.evosuite.symbolic.solver.smt.ExprToSmtVisitor;
import org.evosuite.symbolic.solver.smt.SmtExpr;
import org.evosuite.symbolic.solver.smt.SmtIntConstant;
import org.evosuite.utils.RegexDistanceUtils;

/**
 * Visitor for converting symbolic expressions to CVC4 SMT expressions.
 */
final class ExprToCVC4Visitor extends ExprToSmtVisitor {

    private final boolean approximateNonLinearExpressions;

    /**
     * Constructs an ExprToCVC4Visitor with non-linear approximation disabled.
     */
    public ExprToCVC4Visitor() {
        this(false);
    }

    /**
     * Constructs an ExprToCVC4Visitor.
     *
     * @param rewriteNonLinearExpressions whether to approximate non-linear expressions
     */
    public ExprToCVC4Visitor(boolean rewriteNonLinearExpressions) {
        this.approximateNonLinearExpressions = rewriteNonLinearExpressions;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected SmtExpr postVisit(IntegerBinaryExpression source, SmtExpr left, Operator operator, SmtExpr right) {
        switch (operator) {

            case DIV: {
                if (approximateNonLinearExpressions && left.isSymbolic() && right.isSymbolic()) {
                    SmtExpr concreteRightValue = approximateToConcreteValue(source.getRightOperand());
                    return SmtExprBuilder.mkIntDiv(left, concreteRightValue);
                } else {
                    return super.postVisit(source, left, operator, right);
                }
            }
            case MUL: {
                if (approximateNonLinearExpressions && left.isSymbolic() && right.isSymbolic()) {
                    SmtExpr concreteRightValue = approximateToConcreteValue(source.getRightOperand());
                    return SmtExprBuilder.mkMul(left, concreteRightValue);
                } else {
                    return super.postVisit(source, left, operator, right);
                }
            }
            case REM: {
                if (approximateNonLinearExpressions && left.isSymbolic() && right.isSymbolic()) {
                    SmtExpr concreteRightValue = approximateToConcreteValue(source.getRightOperand());
                    return SmtExprBuilder.mkMod(left, concreteRightValue);
                } else {
                    return super.postVisit(source, left, operator, right);
                }
            }
            case IOR: {
                SmtExpr bvLeft = SmtExprBuilder.mkInt2BV(32, left);
                SmtExpr bvRight = SmtExprBuilder.mkInt2BV(32, right);
                SmtExpr bvor = SmtExprBuilder.mkBVOR(bvLeft, bvRight);
                return mkBV2Int(bvor);
            }
            case IAND: {
                SmtExpr bvLeft = SmtExprBuilder.mkInt2BV(32, left);
                SmtExpr bvRight = SmtExprBuilder.mkInt2BV(32, right);
                SmtExpr bvAnd = SmtExprBuilder.mkBVAND(bvLeft, bvRight);
                return mkBV2Int(bvAnd);
            }
            case IXOR: {
                SmtExpr bvLeft = SmtExprBuilder.mkInt2BV(32, left);
                SmtExpr bvRight = SmtExprBuilder.mkInt2BV(32, right);
                SmtExpr bvXor = SmtExprBuilder.mkBVXOR(bvLeft, bvRight);
                return mkBV2Int(bvXor);
            }

            case SHL: {
                SmtExpr bvLeft = SmtExprBuilder.mkInt2BV(32, left);
                SmtExpr bvRight = SmtExprBuilder.mkInt2BV(32, right);
                SmtExpr bvShl = SmtExprBuilder.mkBVSHL(bvLeft, bvRight);
                return mkBV2Int(bvShl);
            }

            case SHR: {
                SmtExpr bvLeft = SmtExprBuilder.mkInt2BV(32, left);
                SmtExpr bvRight = SmtExprBuilder.mkInt2BV(32, right);
                SmtExpr bvShr = SmtExprBuilder.mkBVASHR(bvLeft, bvRight);
                return mkBV2Int(bvShr);
            }

            case USHR: {
                SmtExpr bvLeft = SmtExprBuilder.mkInt2BV(32, left);
                SmtExpr bvRight = SmtExprBuilder.mkInt2BV(32, right);
                SmtExpr bvShr = SmtExprBuilder.mkBVLSHR(bvLeft, bvRight);
                SmtExpr retVal = mkBV2Int(bvShr);
                return retVal;
            }
            default: {
                return super.postVisit(source, left, operator, right);
            }
        }
    }

    private static SmtExpr mkBV2Int(SmtExpr bv) {
        SmtExpr bv2nat = SmtExprBuilder.mkBV2Nat(bv);
        SmtIntConstant maxIntValue = SmtExprBuilder.mkIntConstant(Integer.MAX_VALUE);
        SmtExpr condExpr = SmtExprBuilder.mkLe(bv2nat, maxIntValue);
        SmtExpr bvMinusOne = SmtExprBuilder.mkInt2BV(32, SmtExprBuilder.mkIntConstant(-1));
        SmtExpr xor = SmtExprBuilder.mkBVXOR(bv, bvMinusOne);
        SmtExpr bvOne = SmtExprBuilder.mkInt2BV(32, SmtExprBuilder.ONE_INT);
        SmtExpr bvAdd = SmtExprBuilder.mkBVADD(xor, bvOne);
        SmtExpr bv2natAdd = SmtExprBuilder.mkBV2Nat(bvAdd);

        SmtExpr thenExpr = bv2nat;
        SmtExpr elseExpr = SmtExprBuilder.mkNeg(bv2natAdd);

        SmtExpr ite = SmtExprBuilder.mkITE(condExpr, thenExpr, elseExpr);
        return ite;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected SmtExpr postVisit(RealBinaryExpression source, SmtExpr left, Operator operator, SmtExpr right) {
        switch (operator) {

            case DIV: {
                if (approximateNonLinearExpressions && left.isSymbolic() && right.isSymbolic()) {
                    SmtExpr concreteRightValue = approximateToConcreteValue(source.getRightOperand());
                    return SmtExprBuilder.mkRealDiv(left, concreteRightValue);
                } else {
                    return super.postVisit(source, left, operator, right);
                }
            }
            case MUL: {
                if (approximateNonLinearExpressions && left.isSymbolic() && right.isSymbolic()) {
                    SmtExpr concreteRightValue = approximateToConcreteValue(source.getRightOperand());
                    return SmtExprBuilder.mkMul(left, concreteRightValue);
                } else {
                    return super.postVisit(source, left, operator, right);
                }
            }
            default: {
                return super.postVisit(source, left, operator, right);
            }
        }

    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected SmtExpr postVisit(StringBinaryComparison source, SmtExpr left, Operator operator, SmtExpr right) {

        switch (operator) {
            case PATTERNMATCHES: {
                String regex = source.getLeftOperand().getConcreteValue();
                String expandedRegex = RegexDistanceUtils.expandRegex(regex);
                RegExp regexp = new RegExp(expandedRegex, RegExp.INTERSECTION);
                RegExpToCVC4Visitor visitor = new RegExpToCVC4Visitor();
                SmtExpr regExpSmtExpr = visitor.visitRegExp(regexp);

                if (regExpSmtExpr == null) {
                    return approximateToConcreteValue(source);
                } else {
                    SmtExpr strInRegExp = SmtExprBuilder.mkStrInRE(right, regExpSmtExpr);
                    return SmtExprBuilder.mkITE(strInRegExp, SmtExprBuilder.ONE_INT, SmtExprBuilder.ZERO_INT);
                }
            }
            default:
                return super.postVisit(source, left, operator, right);
        }
    }

}
