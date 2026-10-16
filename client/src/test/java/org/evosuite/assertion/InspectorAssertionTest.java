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

import com.examples.with.different.packagename.NestedEnumAccessibilityFixture;
import com.examples.with.different.packagename.PrivateNestedEnumFixture;
import com.examples.with.different.packagename.PureEnumFixture;
import org.evosuite.Properties;
import org.evosuite.testcase.variable.VariableReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class InspectorAssertionTest {

    /**
     * Mirrors the SF110 {@code jigl.image.io.ImageInputStream} defect: an
     * {@code assertEquals} comparing an inspector call's result against a private
     * nested enum constant (e.g. {@code imageInputStream0.type()}). The declaring
     * type can never be written in the generated test, not even as
     * {@code Type.CONSTANT} from the same package, so the comparison must be
     * rendered by name instead.
     */
    @Test
    public void testGetCodeForPrivateNestedEnumRendersByName() throws NoSuchMethodException {
        InspectorAssertion assertion = new InspectorAssertion();
        VariableReference source = mock(VariableReference.class);
        when(source.getName()).thenReturn("privateNestedEnumFixture0");

        Object activeStatus = PrivateNestedEnumFixture.activeStatus();
        Inspector inspector = new Inspector(PrivateNestedEnumFixture.class,
                PrivateNestedEnumFixture.class.getDeclaredMethod("status"));

        assertion.source = source;
        assertion.inspector = inspector;
        assertion.value = activeStatus;

        String code = assertion.getCode();
        assertEquals("assertEquals(\"ACTIVE\", ((Enum<?>) privateNestedEnumFixture0.status()).name());", code);
    }

    /**
     * The exact failing case from the independent review: an inspector call on a
     * private nested enum, declared to return {@code Object}. The by-name
     * fallback must cast to {@code Enum<?>} before calling {@code name()}, or the
     * rendered assertion does not compile ({@code cannot find symbol: method
     * name(), location: class java.lang.Object}).
     */
    @Test
    public void testGetCodeForPrivateNestedEnumBehindObjectAccessorCompiles() throws NoSuchMethodException {
        InspectorAssertion assertion = new InspectorAssertion();
        VariableReference source = mock(VariableReference.class);
        when(source.getName()).thenReturn("fixture0");

        Object activeStatus = PrivateNestedEnumFixture.activeStatus();
        Inspector inspector = new Inspector(PrivateNestedEnumFixture.class,
                PrivateNestedEnumFixture.class.getDeclaredMethod("status"));

        assertion.source = source;
        assertion.inspector = inspector;
        assertion.value = activeStatus;

        String code = assertion.getCode();
        GeneratedAssertionCompileCheck.assertCompiles("",
                "com.examples.with.different.packagename.PrivateNestedEnumFixture fixture0 = "
                        + "new com.examples.with.different.packagename.PrivateNestedEnumFixture();",
                code);
    }

    /**
     * Same private enum constant, reached through an inspector method already
     * declared to return the nearest concrete, accessible enum supertype
     * ({@code Enum}) rather than {@code Object}. The cast must still compile.
     */
    @Test
    public void testGetCodeForPrivateNestedEnumBehindEnumSupertypeAccessorCompiles() throws NoSuchMethodException {
        InspectorAssertion assertion = new InspectorAssertion();
        VariableReference source = mock(VariableReference.class);
        when(source.getName()).thenReturn("fixture1");

        Object activeStatus = PrivateNestedEnumFixture.activeStatus();
        Inspector inspector = new Inspector(PrivateNestedEnumFixture.class,
                PrivateNestedEnumFixture.class.getDeclaredMethod("statusAsEnum"));

        assertion.source = source;
        assertion.inspector = inspector;
        assertion.value = activeStatus;

        String code = assertion.getCode();
        GeneratedAssertionCompileCheck.assertCompiles("",
                "com.examples.with.different.packagename.PrivateNestedEnumFixture fixture1 = "
                        + "new com.examples.with.different.packagename.PrivateNestedEnumFixture();",
                code);
    }

    /**
     * A public, top-level enum constant's {@code Type.CONSTANT} rendering must
     * still compile (unchanged nameable branch).
     */
    @Test
    public void testGetCodeForPublicTopLevelEnumCompiles() throws NoSuchMethodException {
        InspectorAssertion assertion = new InspectorAssertion();
        VariableReference source = mock(VariableReference.class);
        when(source.getName()).thenReturn("pureEnumFixtureHolder0");

        Inspector inspector = new Inspector(Object.class, Object.class.getDeclaredMethod("hashCode"));

        assertion.source = source;
        assertion.inspector = inspector;
        assertion.value = PureEnumFixture.ONE;

        String code = assertion.getCode();
        GeneratedAssertionCompileCheck.assertCompiles("",
                "import com.examples.with.different.packagename.PureEnumFixture;",
                "Object pureEnumFixtureHolder0 = new Object();", code);
    }

    /**
     * A public nested enum constant's {@code Type.CONSTANT} rendering must still
     * compile (unchanged nameable branch).
     */
    @Test
    public void testGetCodeForPublicNestedEnumCompiles() throws NoSuchMethodException {
        InspectorAssertion assertion = new InspectorAssertion();
        VariableReference source = mock(VariableReference.class);
        when(source.getName()).thenReturn("nestedEnumAccessibilityFixture0");

        Object activeStatus = NestedEnumAccessibilityFixture.activePublicStatus();
        Inspector inspector = new Inspector(NestedEnumAccessibilityFixture.class,
                NestedEnumAccessibilityFixture.class.getDeclaredMethod("publicStatus"));

        assertion.source = source;
        assertion.inspector = inspector;
        assertion.value = activeStatus;

        String code = assertion.getCode();
        GeneratedAssertionCompileCheck.assertCompiles("",
                "import com.examples.with.different.packagename.NestedEnumAccessibilityFixture;",
                "com.examples.with.different.packagename.NestedEnumAccessibilityFixture "
                        + "nestedEnumAccessibilityFixture0 = "
                        + "new com.examples.with.different.packagename.NestedEnumAccessibilityFixture();",
                code);
    }

    /**
     * A package-private nested enum constant's {@code Type.CONSTANT} rendering,
     * from a generated test in the fixture's own package, must still compile.
     */
    @Test
    public void testGetCodeForPackagePrivateNestedEnumInSamePackageCompiles() throws NoSuchMethodException {
        String oldClassPrefix = Properties.CLASS_PREFIX;
        Properties.CLASS_PREFIX = NestedEnumAccessibilityFixture.class.getPackage().getName();
        try {
            InspectorAssertion assertion = new InspectorAssertion();
            VariableReference source = mock(VariableReference.class);
            when(source.getName()).thenReturn("nestedEnumAccessibilityFixture1");

            Object activeStatus = NestedEnumAccessibilityFixture.activePackagePrivateStatus();
            Inspector inspector = new Inspector(NestedEnumAccessibilityFixture.class,
                    NestedEnumAccessibilityFixture.class.getDeclaredMethod("packagePrivateStatus"));

            assertion.source = source;
            assertion.inspector = inspector;
            assertion.value = activeStatus;

            String code = assertion.getCode();
            GeneratedAssertionCompileCheck.assertCompiles(
                    NestedEnumAccessibilityFixture.class.getPackage().getName(),
                    "NestedEnumAccessibilityFixture nestedEnumAccessibilityFixture1 = "
                            + "new NestedEnumAccessibilityFixture();",
                    code);
        } finally {
            Properties.CLASS_PREFIX = oldClassPrefix;
        }
    }

    /**
     * A public, top-level enum constant must still render exactly as before:
     * {@code Type.CONSTANT} compared directly against the inspector call's result.
     */
    @Test
    public void testGetCodeForPublicTopLevelEnumRendersTypeConstant() throws NoSuchMethodException {
        InspectorAssertion assertion = new InspectorAssertion();
        VariableReference source = mock(VariableReference.class);
        when(source.getName()).thenReturn("pureEnumFixtureHolder0");

        Inspector inspector = new Inspector(Object.class, Object.class.getDeclaredMethod("hashCode"));

        assertion.source = source;
        assertion.inspector = inspector;
        assertion.value = PureEnumFixture.ONE;

        String code = assertion.getCode();
        assertEquals("assertEquals(PureEnumFixture.ONE, pureEnumFixtureHolder0.hashCode());", code);
    }

    /**
     * A public *nested* enum constant must render as {@code Type.CONSTANT} -- the
     * case that previously regressed to a by-name comparison when the
     * enum-assertion path reused the blanket-conservative
     * {@code isClassLiteralReferenceableFromGeneratedTest} (which treats every
     * nested class as unnameable, private or not).
     */
    @Test
    public void testGetCodeForPublicNestedEnumRendersTypeConstant() throws NoSuchMethodException {
        InspectorAssertion assertion = new InspectorAssertion();
        VariableReference source = mock(VariableReference.class);
        when(source.getName()).thenReturn("nestedEnumAccessibilityFixture0");

        Object activeStatus = NestedEnumAccessibilityFixture.activePublicStatus();
        Inspector inspector = new Inspector(NestedEnumAccessibilityFixture.class,
                NestedEnumAccessibilityFixture.class.getDeclaredMethod("publicStatus"));

        assertion.source = source;
        assertion.inspector = inspector;
        assertion.value = activeStatus;

        String code = assertion.getCode();
        assertEquals("assertEquals(NestedEnumAccessibilityFixture.PublicStatus.ACTIVE, "
                + "nestedEnumAccessibilityFixture0.publicStatus());", code);
    }

    /**
     * A package-private nested enum constant, compared from a generated test placed
     * in the same package as the fixture, must also render as {@code Type.CONSTANT}.
     */
    @Test
    public void testGetCodeForPackagePrivateNestedEnumInSamePackageRendersTypeConstant()
            throws NoSuchMethodException {
        String oldClassPrefix = Properties.CLASS_PREFIX;
        Properties.CLASS_PREFIX = NestedEnumAccessibilityFixture.class.getPackage().getName();
        try {
            InspectorAssertion assertion = new InspectorAssertion();
            VariableReference source = mock(VariableReference.class);
            when(source.getName()).thenReturn("nestedEnumAccessibilityFixture0");

            Object activeStatus = NestedEnumAccessibilityFixture.activePackagePrivateStatus();
            Inspector inspector = new Inspector(NestedEnumAccessibilityFixture.class,
                    NestedEnumAccessibilityFixture.class.getDeclaredMethod("packagePrivateStatus"));

            assertion.source = source;
            assertion.inspector = inspector;
            assertion.value = activeStatus;

            String code = assertion.getCode();
            assertEquals("assertEquals(NestedEnumAccessibilityFixture.PackagePrivateStatus.ACTIVE, "
                    + "nestedEnumAccessibilityFixture0.packagePrivateStatus());", code);
        } finally {
            Properties.CLASS_PREFIX = oldClassPrefix;
        }
    }
}
