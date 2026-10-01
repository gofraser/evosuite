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
package org.evosuite.assertion;

import org.evosuite.testcase.TestCodeVisitor;
import org.evosuite.utils.NumberFormatter;

/**
 * Renders an {@code assertEquals} comparing a known enum constant against the
 * generated-test expression that is expected to yield it. Shared by
 * {@link PrimitiveAssertion} and {@link InspectorAssertion} (via the
 * visitor-less {@link #getEnumAssertionCode(Object, String)}) and by
 * {@link TestCodeVisitor}'s own {@code visitPrimitiveAssertion}/
 * {@code visitInspectorAssertion} (via
 * {@link #getEnumAssertionCode(Object, String, TestCodeVisitor)}), whose enum
 * handling is otherwise identical except for how they compute
 * {@code actualExpression} (a bare variable name versus an inspector-method
 * call on one) and whether a {@link TestCodeVisitor} is available to track
 * imports and resolve naming conflicts.
 */
public final class EnumAssertionCodeRenderer {

    private EnumAssertionCodeRenderer() {
    }

    /**
     * The constant's declaring class may be a source-private nested enum, whose
     * name can never be written in the generated test -- not even as
     * {@code Type.CONSTANT} from the same package -- and there is no reflective
     * escape hatch here (unlike a {@code Class} literal), since the expression
     * must denote the constant's value, not a {@code Class}. When {@link
     * TestCodeVisitor#isEnumConstantNameableInGeneratedTest} says the declaring
     * class cannot be named, the comparison is rendered by name instead,
     * comparing {@link Enum#name()} strings rather than the constants
     * themselves.
     *
     * <p>In that by-name fallback, {@code actualExpression} cannot be called
     * directly: its declared/static type in the generated test need not be the
     * enum type (often {@code Object}, e.g. an accessor declared to return
     * {@code Object}, or an interface the enum implements), and neither has a
     * {@code name()} method. The expression is therefore cast to {@code Enum<?>}
     * first; that cast is always compile-safe here, since an enum constant's
     * declared type can only be {@code Object}, {@code Enum} itself, or an
     * interface the enum implements. {@code name()} is used rather than
     * {@code toString()} because an enum may override {@code toString()}, which
     * then need not agree with {@code name()}.
     *
     * @param value            the enum constant being compared against
     * @param actualExpression the generated-test expression yielding the actual
     *                         value
     * @return the rendered {@code assertEquals} statement
     */
    public static String getEnumAssertionCode(Object value, String actualExpression) {
        return getEnumAssertionCode(value, actualExpression, null);
    }

    /**
     * As {@link #getEnumAssertionCode(Object, String)}, but also given the
     * {@link TestCodeVisitor} rendering the surrounding test, when one is
     * available ({@link PrimitiveAssertion} and {@link InspectorAssertion}'s own
     * {@code getCode()} pass {@code null} and get identical behaviour to the
     * two-argument overload).
     *
     * <p>In the nameable branch, the visitor -- when given -- is threaded into
     * {@link NumberFormatter#getNumberString(Object, TestCodeVisitor)}, which
     * gates its own import-registering, conflict-resolving naming on the same
     * {@link TestCodeVisitor#isEnumConstantNameableInGeneratedTest} check used
     * above, so it already calls {@link TestCodeVisitor#getClassName(Class)} on
     * {@code enumClass} (and its enclosing class, if nested) whenever this
     * branch is reached. The explicit follow-up call below is therefore a no-op
     * in practice; it is kept only as a defensive safeguard against that
     * invariant changing.
     *
     * @param visitor the enclosing visitor, or {@code null} outside a visitor pass
     */
    public static String getEnumAssertionCode(Object value, String actualExpression, TestCodeVisitor visitor) {
        Class<?> enumClass = value.getClass();
        if (enumClass.isAnonymousClass()) {
            enumClass = enumClass.getEnclosingClass();
        }
        if (TestCodeVisitor.isEnumConstantNameableInGeneratedTest(enumClass)) {
            String rendered = "assertEquals(" + NumberFormatter.getNumberString(value, visitor) + ", "
                    + actualExpression + ");";
            if (visitor != null) {
                // Make sure the enum (and its enclosing classes, if nested) is
                // imported in the JUnit test -- see the note above on why this
                // cannot be left to NumberFormatter alone.
                visitor.getClassName(enumClass);
            }
            return rendered;
        }
        return "assertEquals(" + NumberFormatter.getNumberString(((Enum<?>) value).name()) + ", "
                + "((Enum<?>) " + actualExpression + ").name());";
    }
}
