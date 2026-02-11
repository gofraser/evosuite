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
import dk.brics.automaton.RegExpVisitor;
import org.evosuite.symbolic.solver.SmtExprBuilder;
import org.evosuite.symbolic.solver.smt.SmtExpr;
import org.evosuite.symbolic.solver.smt.SmtIntConstant;
import org.evosuite.symbolic.solver.smt.SmtStringConstant;

/**
 * Visitor for converting regular expressions to CVC4 SMT expressions.
 */
public final class RegExpToCVC4Visitor extends RegExpVisitor<SmtExpr> {

    /**
     * Constructs a RegExpToCVC4Visitor.
     */
    public RegExpToCVC4Visitor() {
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public SmtExpr visitUnion(RegExp left, RegExp right) {
        SmtExpr leftExpr = visitRegExp(left);
        SmtExpr rightExpr = visitRegExp(right);
        if (leftExpr == null || rightExpr == null) {
            return null;
        }
        SmtExpr unionExpr = SmtExprBuilder.mkREUnion(leftExpr, rightExpr);
        return unionExpr;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public SmtExpr visitString(String s) {
        SmtStringConstant strConstant = SmtExprBuilder.mkStringConstant(s);
        SmtExpr strToRegExpr = SmtExprBuilder.mkStrToRE(strConstant);
        return strToRegExpr;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public SmtExpr visitRepeatMinMax(RegExp e, int min, int max) {
        SmtExpr regExpr = this.visitRegExp(e);
        if (regExpr == null) {
            return null;
        }
        SmtIntConstant minExpr = SmtExprBuilder.mkIntConstant(min);
        SmtIntConstant maxExpr = SmtExprBuilder.mkIntConstant(max);
        SmtExpr loopExpr = SmtExprBuilder.mkLoop(regExpr, minExpr, maxExpr);
        return loopExpr;

    }

    /**
     * {@inheritDoc}
     */
    @Override
    public SmtExpr visitRepeatMin(RegExp e, int min) {
        SmtExpr regExpr = this.visitRegExp(e);
        if (regExpr == null) {
            return null;
        }
        if (min == 1) {
            SmtExpr kleeneCrossExpr = SmtExprBuilder.mkREKleeneCross(regExpr);
            return kleeneCrossExpr;
        } else {
            SmtIntConstant minExpr = SmtExprBuilder.mkIntConstant(min);
            SmtExpr loopExpr = SmtExprBuilder.mkLoop(regExpr, minExpr);
            return loopExpr;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public SmtExpr visitRepeat(RegExp arg) {
        SmtExpr expr = this.visitRegExp(arg);
        if (expr == null) {
            return null;
        }
        SmtExpr repeatExpr = SmtExprBuilder.mkREKleeneStar(expr);
        return repeatExpr;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public SmtExpr visitOptional(RegExp e) {
        SmtExpr expr = this.visitRegExp(e);
        if (expr == null) {
            return null;
        }
        SmtExpr optExpr = SmtExprBuilder.mkREOpt(expr);
        return optExpr;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public SmtExpr visitConcatenation(RegExp left, RegExp right) {
        SmtExpr leftExpr = this.visitRegExp(left);
        SmtExpr rightExpr = this.visitRegExp(right);
        if (leftExpr == null || rightExpr == null) {
            return null;
        }
        SmtExpr concat = SmtExprBuilder.mkREConcat(leftExpr, rightExpr);
        return concat;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public SmtExpr visitCharRange(char from, char to) {
        String fromStr = String.valueOf(from);
        SmtStringConstant fromConstant = SmtExprBuilder
                .mkStringConstant(fromStr);

        String toStr = String.valueOf(to);
        SmtStringConstant toConstant = SmtExprBuilder.mkStringConstant(toStr);

        SmtExpr rangeExpr = SmtExprBuilder.mkRERange(fromConstant,
                toConstant);
        return rangeExpr;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public SmtExpr visitChar(char c) {
        String str = String.valueOf(c);
        SmtStringConstant strConstant = SmtExprBuilder.mkStringConstant(str);
        SmtExpr expr = SmtExprBuilder.mkStrToRE(strConstant);
        return expr;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public SmtExpr visitAnyChar() {
        return SmtExprBuilder.mkREAllChar();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public SmtExpr visitInterval(int min, int max) {
        throw new IllegalStateException(
                "Optional dk.brics productions are not supported. Check syntax_flags of RegExp(String,String)");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public SmtExpr visitIntersection(RegExp left, RegExp right) {
        throw new IllegalStateException(
                "Optional dk.brics productions are not supported. Check syntax_flags of RegExp(String,String)");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public SmtExpr visitAutomaton(RegExp e) {
        throw new IllegalStateException(
                "Optional dk.brics productions are not supported. Check syntax_flags of RegExp(String,String)");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public SmtExpr visitComplement(RegExp e) {
        throw new IllegalStateException(
                "Optional dk.brics productions are not supported. Check syntax_flags of RegExp(String,String)");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public SmtExpr visitEmpty() {
        throw new IllegalStateException(
                "Optional dk.brics productions are not supported. Check syntax_flags of RegExp(String,String)");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public SmtExpr visitAnyString() {
        throw new IllegalStateException(
                "Optional dk.brics productions are not supported. Check syntax_flags of RegExp(String,String)");
    }

}
