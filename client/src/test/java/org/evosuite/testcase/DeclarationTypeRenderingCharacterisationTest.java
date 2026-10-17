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
package org.evosuite.testcase;

import org.apache.commons.lang3.reflect.TypeUtils;
import org.evosuite.Properties;
import org.evosuite.testcase.accessfixture.TableBase;
import org.evosuite.testcase.accessfixture.other.SecretHolder;
import org.evosuite.testcase.statements.ArrayStatement;
import org.evosuite.testcase.statements.AssignmentStatement;
import org.evosuite.testcase.statements.ClassPrimitiveStatement;
import org.evosuite.testcase.statements.ConstructorStatement;
import org.evosuite.testcase.statements.EnumPrimitiveStatement;
import org.evosuite.testcase.statements.FieldStatement;
import org.evosuite.testcase.statements.FunctionalMockStatement;
import org.evosuite.testcase.statements.MethodStatement;
import org.evosuite.testcase.statements.NullStatement;
import org.evosuite.testcase.statements.StringPrimitiveStatement;
import org.evosuite.testcase.statements.numeric.IntPrimitiveStatement;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.testcase.variable.VariableReferenceImpl;
import org.evosuite.utils.generic.GenericClassFactory;
import org.evosuite.utils.generic.GenericConstructor;
import org.evosuite.utils.generic.GenericField;
import org.evosuite.utils.generic.GenericMethod;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Type;
import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the type each kind of statement declares its variable with, so that a change to the
 * declaration policy of one statement kind is visible.
 */
public class DeclarationTypeRenderingCharacterisationTest {

    private String originalTargetClass;
    private String originalClassPrefix;

    @BeforeEach
    public void setUp() {
        originalTargetClass = Properties.TARGET_CLASS;
        originalClassPrefix = Properties.CLASS_PREFIX;
        Properties.TARGET_CLASS = TableBase.class.getName();
        Properties.resetTargetClass();
        Properties.CLASS_PREFIX = TableBase.class.getPackage().getName();
    }

    @AfterEach
    public void tearDown() {
        Properties.TARGET_CLASS = originalTargetClass;
        Properties.CLASS_PREFIX = originalClassPrefix;
        Properties.resetTargetClass();
    }

    private static String render(TestCase tc) {
        TestCodeVisitor visitor = new TestCodeVisitor();
        tc.accept(visitor);
        return visitor.getCode();
    }

    private static Class<?> secretClass() throws ClassNotFoundException {
        return Class.forName(SecretHolder.class.getName() + "$Secret");
    }

    @Test
    public void nullOfAnAccessibleAndOfAPrivateClass() throws Exception {
        TestCase tc = new DefaultTestCase();
        tc.addStatement(new NullStatement(tc, String.class));
        tc.addStatement(new NullStatement(tc, secretClass()));
        assertEquals("String nullRef0 = null;\n"
                + "Object nullRef1 = null;\n", render(tc));
    }

    @Test
    public void constructorAndMethod() throws Exception {
        TestCase tc = new DefaultTestCase();
        Type listOfString = TypeUtils.parameterize(ArrayList.class, String.class);
        VariableReference list = tc.addStatement(new ConstructorStatement(tc,
                new GenericConstructor(ArrayList.class.getConstructor(), listOfString),
                Collections.emptyList()));
        VariableReference index = tc.addStatement(new IntPrimitiveStatement(tc, 0));
        VariableReference element = tc.addStatement(new MethodStatement(tc,
                new GenericMethod(ArrayList.class.getMethod("get", int.class), listOfString), list,
                Collections.singletonList(index)));
        tc.addStatement(new MethodStatement(tc, new GenericMethod(String.class.getMethod("length"),
                String.class), element, Collections.emptyList()));
        assertEquals("ArrayList<String> arrayList0 = new ArrayList<String>();\n"
                + "int int0 = 0;\n"
                + "String string0 = arrayList0.get(int0);\n"
                + "string0.length();\n", render(tc));
    }

    @Test
    public void fieldRead() throws Exception {
        TestCase tc = new DefaultTestCase();
        VariableReference holder = tc.addStatement(new ConstructorStatement(tc,
                new GenericConstructor(SecretHolder.class.getConstructor(), SecretHolder.class),
                Collections.emptyList()));
        tc.addStatement(new FieldStatement(tc,
                new GenericField(SecretHolder.class.getField("anything"), SecretHolder.class), holder));
        assertEquals("SecretHolder secretHolder0 = new SecretHolder();\n"
                + "Object object0 = secretHolder0.anything;\n", render(tc));
    }

    @Test
    public void primitivesStringsClassLiteralsAndEnums() throws Exception {
        TestCase tc = new DefaultTestCase();
        tc.addStatement(new IntPrimitiveStatement(tc, 7));
        tc.addStatement(new StringPrimitiveStatement(tc, "x"));
        tc.addStatement(new StringPrimitiveStatement(tc, null));
        tc.addStatement(new ClassPrimitiveStatement(tc, String.class));
        tc.addStatement(new ClassPrimitiveStatement(tc, int.class));
        tc.addStatement(new EnumPrimitiveStatement<>(tc, DayOfWeek.MONDAY));
        assertEquals("int int0 = 7;\n"
                + "String string0 = \"x\";\n"
                + "String string1 = null;\n"
                + "Class<String> class0 = String.class;\n"
                + "Class<?> class1 = int.class;\n"
                + "DayOfWeek dayOfWeek0 = DayOfWeek.MONDAY;\n", render(tc));
    }

    @Test
    public void arrayAndAssignmentToANewLocal() throws Exception {
        TestCase tc = new DefaultTestCase();
        tc.addStatement(new ArrayStatement(tc, String[].class, 2));
        VariableReference text = tc.addStatement(new StringPrimitiveStatement(tc, "x"));
        tc.addStatement(new AssignmentStatement(tc, new VariableReferenceImpl(tc, String.class), text));
        assertEquals("String[] stringArray0 = new String[2];\n"
                + "String string0 = \"x\";\n"
                + "String string1 = string0;\n", render(tc));
    }

    @Test
    public void mockOfAParameterizedType() throws Exception {
        TestCase tc = new DefaultTestCase();
        Type listOfString = TypeUtils.parameterize(List.class, String.class);
        tc.addStatement(new FunctionalMockStatement(tc, listOfString, GenericClassFactory.get(List.class)));
        assertEquals("List<String> list0 = (List<String>) mock(List.class, withSettings().stubOnly().invocationListeners(new MockInvocationLimit()).defaultAnswer(new ViolatedAssumptionAnswer()));\n", render(tc));
    }
}
