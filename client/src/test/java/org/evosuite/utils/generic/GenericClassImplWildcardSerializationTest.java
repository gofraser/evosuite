/*
 * Copyright (C) 2010-2026 Gordon Fraser, Andrea Arcuri and EvoSuite
 * contributors
 *
 * This file is part of EvoSuite.
 *
 * EvoSuite is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published
 * by the Free Software Foundation, either version 2 of the License, or
 * (at your option) any later version.
 */
package org.evosuite.utils.generic;

import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.TestCodeVisitor;
import org.evosuite.testcase.statements.AssignmentStatement;
import org.evosuite.testcase.statements.MethodStatement;
import org.evosuite.testcase.variable.VariableReference;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenericClassImplWildcardSerializationTest {

    private static class WildcardTypes {
        Class<?> classWildcard;
        Class<? extends Number> classExtendsNumber;
        List<Class<?>> listOfClassWildcard;
        Iterator<Comparable<?>> iteratorOfComparableWildcard;
        List<? super Number> listOfSuperNumber;
    }

    private static class WildcardReturnMethods {
        static Class<? extends Number> classExtendsNumber() {
            return Number.class;
        }
    }

    @Test
    void preservesWildcardsAcrossGenericClassSerialization() throws Exception {
        for (Field field : WildcardTypes.class.getDeclaredFields()) {
            GenericClassImpl original = new GenericClassImpl(field.getGenericType());
            GenericClassImpl recovered = roundTrip(original);

            assertEquals(original.getTypeName(), recovered.getTypeName(), field.getName());
            assertFalse(recovered.getTypeName().contains("java.lang.Object"), field.getName());
            assertFalse(recovered.getTypeName().contains("Class<java.lang.Object>"), field.getName());
        }
    }

    @Test
    void preservesWildcardMethodReturnTypeWhenRenderingRoundTrippedTestCase() throws Exception {
        DefaultTestCase original = new DefaultTestCase();
        Method method = WildcardReturnMethods.class.getDeclaredMethod("classExtendsNumber");
        MethodStatement methodStatement = new MethodStatement(original,
                new GenericMethod(method, WildcardReturnMethods.class), null, Arrays.asList());
        VariableReference returnValue = original.addStatement(methodStatement);
        original.addStatement(new AssignmentStatement(original, returnValue, returnValue));

        DefaultTestCase recovered = roundTrip(original);
        TestCodeVisitor visitor = new TestCodeVisitor();
        recovered.accept(visitor);
        String rendered = visitor.getCode();

        assertTrue(rendered.contains("Class<?"), rendered);
        assertFalse(rendered.contains("Class<Object>"), rendered);
    }

    private static GenericClassImpl roundTrip(GenericClassImpl original) throws Exception {
        return (GenericClassImpl) deserialize(serialize(original));
    }

    private static byte[] serialize(Object value) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
            output.writeObject(value);
        }
        return bytes.toByteArray();
    }

    private static DefaultTestCase roundTrip(DefaultTestCase original) throws Exception {
        return (DefaultTestCase) deserialize(serialize(original));
    }

    private static Object deserialize(byte[] bytes) throws Exception {
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes))) {
            return input.readObject();
        }
    }
}
