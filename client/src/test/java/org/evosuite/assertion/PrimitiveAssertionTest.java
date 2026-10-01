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
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class PrimitiveAssertionTest {

    /**
     * A public, top-level enum constant must still render exactly as before:
     * {@code Type.CONSTANT} compared directly against the source variable.
     */
    @Test
    public void testGetCodeForPublicTopLevelEnumRendersTypeConstant() {
        PrimitiveAssertion assertion = new PrimitiveAssertion();
        VariableReference source = mock(VariableReference.class);
        doReturn(PureEnumFixture.class).when(source).getVariableClass();
        when(source.getName()).thenReturn("pureEnumFixture0");

        assertion.source = source;
        assertion.value = PureEnumFixture.ONE;

        String code = assertion.getCode();
        assertEquals("assertEquals(PureEnumFixture.ONE, pureEnumFixture0);", code);
    }

    /**
     * A private nested enum constant (mirrors
     * {@code jigl.image.io.ImageInputStream.ImageFileType}) cannot be named as
     * {@code Type.CONSTANT} -- its declaring type can never be written in the
     * generated test. The comparison must instead be rendered by name.
     */
    @Test
    public void testGetCodeForPrivateNestedEnumRendersByName() {
        PrimitiveAssertion assertion = new PrimitiveAssertion();
        VariableReference source = mock(VariableReference.class);
        Object activeStatus = PrivateNestedEnumFixture.activeStatus();
        doReturn(activeStatus.getClass()).when(source).getVariableClass();
        when(source.getName()).thenReturn("privateNestedEnumFixture0");

        assertion.source = source;
        assertion.value = activeStatus;

        String code = assertion.getCode();
        assertEquals("assertEquals(\"ACTIVE\", privateNestedEnumFixture0.name());", code);
    }

    /**
     * A public *nested* enum constant must render as {@code Type.CONSTANT}, same as
     * the top-level case -- this is the case that previously regressed to a by-name
     * comparison when the enum-assertion path reused the blanket-conservative
     * {@code isClassLiteralReferenceableFromGeneratedTest} (which treats every
     * nested class as unnameable, private or not).
     */
    @Test
    public void testGetCodeForPublicNestedEnumRendersTypeConstant() {
        PrimitiveAssertion assertion = new PrimitiveAssertion();
        VariableReference source = mock(VariableReference.class);
        Object activeStatus = NestedEnumAccessibilityFixture.activePublicStatus();
        doReturn(activeStatus.getClass()).when(source).getVariableClass();
        when(source.getName()).thenReturn("nestedEnumAccessibilityFixture0");

        assertion.source = source;
        assertion.value = activeStatus;

        String code = assertion.getCode();
        assertEquals("assertEquals(NestedEnumAccessibilityFixture.PublicStatus.ACTIVE, "
                + "nestedEnumAccessibilityFixture0);", code);
    }

    /**
     * A package-private nested enum constant, compared from a generated test placed
     * in the same package as the fixture, must also render as {@code Type.CONSTANT}.
     */
    @Test
    public void testGetCodeForPackagePrivateNestedEnumInSamePackageRendersTypeConstant() {
        String oldClassPrefix = Properties.CLASS_PREFIX;
        Properties.CLASS_PREFIX = NestedEnumAccessibilityFixture.class.getPackage().getName();
        try {
            PrimitiveAssertion assertion = new PrimitiveAssertion();
            VariableReference source = mock(VariableReference.class);
            Object activeStatus = NestedEnumAccessibilityFixture.activePackagePrivateStatus();
            doReturn(activeStatus.getClass()).when(source).getVariableClass();
            when(source.getName()).thenReturn("nestedEnumAccessibilityFixture0");

            assertion.source = source;
            assertion.value = activeStatus;

            String code = assertion.getCode();
            assertEquals("assertEquals(NestedEnumAccessibilityFixture.PackagePrivateStatus.ACTIVE, "
                    + "nestedEnumAccessibilityFixture0);", code);
        } finally {
            Properties.CLASS_PREFIX = oldClassPrefix;
        }
    }
}
