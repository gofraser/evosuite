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
     * The constant's declaring class may be a source-private nested enum (e.g.
     * {@code jigl.image.io.ImageInputStream.ImageFileType}), whose name can never
     * be written -- not even as {@code Type.CONSTANT} from the same package.
     * There is no reflective escape hatch here (unlike a {@code Class} literal):
     * the expression must denote the constant's value, not a {@code Class}. So
     * when {@link TestCodeVisitor#isEnumConstantNameableInGeneratedTest} says the
     * declaring class cannot be named, the comparison is rendered by name
     * instead, comparing {@link Enum#name()} strings rather than the constants
     * themselves. See that method's javadoc for why this uses it rather than the
     * stricter, blanket-conservative {@code isClassLiteralReferenceableFromGeneratedTest}
     * used for {@code .class} literals: unlike a {@code .class} literal, falling
     * back unnecessarily here is not free, so the check needs to be accurate
     * rather than merely safe.
     *
     * <p>In the by-name fallback, {@code actualExpression} cannot be called
     * directly -- its declared/static type in the generated test need not be the
     * enum type (it is often {@code Object}, e.g. an accessor declared to return
     * {@code Object}, or an interface the enum implements), and
     * {@code Object}/arbitrary interfaces have no {@code name()} method. The
     * expression is therefore cast to {@code Enum<?>} before calling
     * {@code name()}. That cast is always compile-safe here: this branch is
     * reached only when {@code value} is an actual enum constant, and an enum
     * instance is a reference type (there is no primitive enum type), so
     * {@code actualExpression}'s declared type can only be {@code Object},
     * {@code Enum} itself, or an interface the enum implements (an enum cannot
     * have any other superclass) -- and javac permits a cast to a non-final class
     * ({@link Enum} is abstract) from any of those, per the narrowing reference
     * conversion rules (JLS 5.5): a cast between an interface type and a
     * non-final class is always allowed, since some not-yet-known subtype could
     * implement both. {@code toString()} was considered instead of a cast -- it
     * needs no cast, since every reference type declares it -- but an enum may
     * override {@code toString()}, so it is not guaranteed to agree with
     * {@code name()}; the cast preserves the exact comparison this fallback
     * already made before the cast was added, so it was kept.
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
     * available (callers outside a visitor pass, i.e. {@link PrimitiveAssertion}
     * and {@link InspectorAssertion}'s own {@code getCode()}, pass {@code null}
     * and get identical behaviour to the two-argument overload).
     *
     * <p>In the nameable branch, the visitor -- when given -- is threaded into
     * {@link NumberFormatter#getNumberString(Object, TestCodeVisitor)} exactly
     * as {@code TestCodeVisitor}'s own (now-removed) direct calls did, so
     * {@code TestCodeVisitor}'s richer, import-aware, conflict-resolving
     * naming is unchanged for every case it already handled. {@link
     * NumberFormatter}'s own gate for that richer path uses the same {@link
     * TestCodeVisitor#isEnumConstantNameableInGeneratedTest} check this method
     * just used to decide this branch is reachable at all, so whenever this
     * branch is taken, {@code NumberFormatter} already calls {@link
     * TestCodeVisitor#getClassName(Class)} on {@code enumClass} (and, if
     * nested, its enclosing class) itself, registering the import and
     * resolving any simple-name clash with another already-imported class.
     * The explicit follow-up call below is therefore a no-op in every case
     * this method reaches (it just returns the now-cached name) and is kept
     * only as a defensive safeguard: it used to be load-bearing, back when
     * {@code NumberFormatter} gated its richer path on the strictly narrower,
     * blanket-nested-class-rejecting {@link
     * TestCodeVisitor#isClassLiteralReferenceableFromGeneratedTest} instead,
     * which silently skipped import registration (and conflict resolution)
     * for every nested enum -- the exact mismatch that produced a
     * plausible-looking but wrongly-resolved {@code Outer.Inner.CONSTANT}
     * reference when some unrelated, already-imported class happened to share
     * the enum's enclosing simple name.
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
