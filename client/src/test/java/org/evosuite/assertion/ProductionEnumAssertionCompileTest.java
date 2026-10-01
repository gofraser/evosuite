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

import com.examples.with.different.packagename.ConstantBodyEnumProducer;
import com.examples.with.different.packagename.NestedConstantBodyEnumFixture;
import com.examples.with.different.packagename.PrivateNestedEnumFixture;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.TestCodeVisitor;
import org.evosuite.testcase.TestFactory;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.generic.GenericConstructor;
import org.evosuite.utils.generic.GenericMethod;
import org.junit.jupiter.api.Test;

/**
 * Drives the actual production test-writing route -- {@link DefaultTestCase#toCode()},
 * via {@link TestCodeVisitor}'s own {@code visitPrimitiveAssertion}/
 * {@code visitInspectorAssertion} -- rather than the isolated
 * {@code Assertion#getCode()} strings that {@link PrimitiveAssertionTest} and
 * {@link InspectorAssertionTest} exercise. {@code TestCodeVisitor} dispatches
 * enum-valued primitive/inspector assertions through its own, independent enum
 * branches instead of {@code getCode()} or the shared
 * {@link EnumAssertionCodeRenderer}, so a fix to the latter alone does not
 * repair what {@code toCode()} actually emits. Every assertion here is
 * compiled, never string-matched, so a reachable-but-inaccessible or
 * reachable-but-unimported rendering fails the way a real generated suite
 * would -- at javac time.
 */
public class ProductionEnumAssertionCompileTest {

    /**
     * The independent review's exact reproduction: a private nested enum
     * constant reached through a {@code PrimitiveAssertion} on an accessor
     * declared to return {@code Object}, rendered through the real
     * {@code toCode()} path. Before the fix, {@code TestCodeVisitor}'s own
     * {@code visitPrimitiveAssertion} calls {@code NumberFormatter} directly
     * and renders {@code PrivateNestedEnumFixture.Status.ACTIVE}, which javac
     * rejects as inaccessible.
     */
    @Test
    public void testToCodePrimitiveAssertionOnPrivateNestedEnumBehindObjectAccessor() throws Exception {
        TestCase tc = new DefaultTestCase();
        VariableReference fixture = TestFactory.getInstance().addConstructor(tc,
                new GenericConstructor(PrivateNestedEnumFixture.class.getDeclaredConstructor(),
                        PrivateNestedEnumFixture.class),
                0, 0);
        VariableReference actual = TestFactory.getInstance().addMethodFor(tc, fixture,
                new GenericMethod(PrivateNestedEnumFixture.class.getDeclaredMethod("status"),
                        PrivateNestedEnumFixture.class),
                1);

        PrimitiveAssertion assertion = new PrimitiveAssertion();
        assertion.source = actual;
        assertion.value = PrivateNestedEnumFixture.activeStatus();
        tc.getStatement(actual.getStPosition()).addAssertion(assertion);

        assertCompilesAsProductionTest(tc);
    }

    /**
     * Same private nested enum, same {@code Object}-typed accessor, but
     * reached through an {@code InspectorAssertion} (an inspector call on the
     * accessor) rather than a direct {@code PrimitiveAssertion}. {@code
     * TestCodeVisitor.visitInspectorAssertion} takes the same direct-formatter
     * route as the primitive case.
     */
    @Test
    public void testToCodeInspectorAssertionOnPrivateNestedEnumBehindObjectAccessor() throws Exception {
        TestCase tc = new DefaultTestCase();
        VariableReference fixture = TestFactory.getInstance().addConstructor(tc,
                new GenericConstructor(PrivateNestedEnumFixture.class.getDeclaredConstructor(),
                        PrivateNestedEnumFixture.class),
                0, 0);

        Inspector inspector = new Inspector(PrivateNestedEnumFixture.class,
                PrivateNestedEnumFixture.class.getDeclaredMethod("status"));
        InspectorAssertion assertion = new InspectorAssertion();
        assertion.source = fixture;
        assertion.inspector = inspector;
        assertion.value = PrivateNestedEnumFixture.activeStatus();
        tc.getStatement(fixture.getStPosition()).addAssertion(assertion);

        assertCompilesAsProductionTest(tc);
    }

    /**
     * A <em>public</em>, perfectly nameable nested enum whose constants have
     * their own class bodies ({@link NestedConstantBodyEnumFixture.Mode}), so
     * {@code value.getClass()} is an anonymous subclass and {@code
     * value.getClass().isEnum()} is {@code false} even though {@code value
     * instanceof Enum} is {@code true}. Before the fix, {@code
     * TestCodeVisitor.visitPrimitiveAssertion}'s {@code isEnum()}-only guard
     * misses this branch entirely and falls through to the generic formatter,
     * whose nested-class path renders {@code NestedConstantBodyEnumFixture.Mode.FAST}
     * by simple name but never registers the import for
     * {@code NestedConstantBodyEnumFixture} -- unlike the dedicated enum
     * branch, which does. The result is a plausible-looking but unimported,
     * non-compiling reference.
     *
     * <p>{@link ConstantBodyEnumProducer} is used as the accessor's declaring
     * class, rather than {@code NestedConstantBodyEnumFixture} itself, so
     * that {@code NestedConstantBodyEnumFixture} is never otherwise
     * constructed or named in the generated test -- only the assertion's own
     * enum rendering can account for importing it.
     */
    @Test
    public void testToCodePrimitiveAssertionOnPublicConstantBodyNestedEnumBehindObjectAccessor() throws Exception {
        TestCase tc = new DefaultTestCase();
        VariableReference producer = TestFactory.getInstance().addConstructor(tc,
                new GenericConstructor(ConstantBodyEnumProducer.class.getDeclaredConstructor(),
                        ConstantBodyEnumProducer.class),
                0, 0);
        VariableReference actual = TestFactory.getInstance().addMethodFor(tc, producer,
                new GenericMethod(ConstantBodyEnumProducer.class.getDeclaredMethod("produce"),
                        ConstantBodyEnumProducer.class),
                1);

        PrimitiveAssertion assertion = new PrimitiveAssertion();
        assertion.source = actual;
        assertion.value = NestedConstantBodyEnumFixture.Mode.FAST;
        tc.getStatement(actual.getStPosition()).addAssertion(assertion);

        assertCompilesAsProductionTest(tc);
    }

    /**
     * Renders {@code tc} exactly as the real test writer would -- {@code
     * toCode()}'s method body plus the same visitor's recorded
     * {@code getImports()} -- and compiles the result, rather than
     * string-matching it.
     */
    private static void assertCompilesAsProductionTest(TestCase tc) {
        TestCodeVisitor visitor = new TestCodeVisitor();
        tc.accept(visitor);

        StringBuilder imports = new StringBuilder();
        for (Class<?> imported : visitor.getImports()) {
            imports.append("import ").append(imported.getCanonicalName()).append(";\n");
        }

        GeneratedAssertionCompileCheck.assertCompiles("", imports.toString(), "", visitor.getCode());
    }
}
