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
