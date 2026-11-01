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

package org.evosuite.llm.postprocess;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExprTypeTest {

    @Test
    void arrayTypesPreserveEveryDimension() {
        ExprType type = ExprType.fromTypeName("String[][]");

        assertEquals("java.lang.String[][]", type.typeName);
        assertEquals("java.lang.String", type.componentType);
        assertEquals(2, type.arrayDepth);
    }

    @Test
    void canonicalizationCoversBuiltInImmutableSimpleNames() {
        assertEquals("java.util.UUID", ExprType.canonicalName("UUID"));
        assertEquals("java.time.LocalDate", ExprType.canonicalName("LocalDate"));
        assertEquals("java.time.Duration", ExprType.canonicalName("Duration"));
        assertTrue(ExprType.reference("Boolean").isBooleanLike());
        assertTrue(ExprType.reference("Float").isFloatLike());
    }

    @Test
    void methodDescriptorsExposeParsedTypesWithoutRawDescriptorAccessors() {
        JvmMethodDescriptor descriptor = JvmMethodDescriptor.parse(
                "([[Ljava/lang/String;I)Ljava/time/LocalDate;");

        assertTrue(descriptor.isValid());
        assertEquals(2, descriptor.argumentCount());
        assertEquals("java.lang.String[][]", descriptor.parameterTypes().get(0).typeName);
        assertEquals("int", descriptor.parameterTypes().get(1).typeName);
        assertEquals("java.time.LocalDate", descriptor.returnType().typeName);
    }

    @Test
    void methodDescriptorsPreserveVoidReturnsAndPrimitiveArrays() {
        JvmMethodDescriptor descriptor = JvmMethodDescriptor.parse("([[I[[Ljava/lang/String;)V");

        assertTrue(descriptor.isValid());
        assertEquals(2, descriptor.argumentCount());
        assertEquals("int[][]", descriptor.parameterTypes().get(0).typeName);
        assertEquals("java.lang.String[][]", descriptor.parameterTypes().get(1).typeName);
        assertFalse(descriptor.returnType().isKnown());
    }

    @Test
    void malformedMethodDescriptorsRemainInvalid() {
        assertNull(JvmMethodDescriptor.parse(null));

        JvmMethodDescriptor missingReturn = JvmMethodDescriptor.parse("(I)");
        JvmMethodDescriptor invalidParameter = JvmMethodDescriptor.parse("(Q)V");

        assertFalse(missingReturn.isValid());
        assertFalse(invalidParameter.isValid());
        assertEquals(0, missingReturn.argumentCount());
        assertEquals(0, invalidParameter.argumentCount());
    }
}
