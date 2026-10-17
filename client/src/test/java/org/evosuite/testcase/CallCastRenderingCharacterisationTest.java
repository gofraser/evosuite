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

import com.examples.with.different.packagename.AccessExamples;
import com.examples.with.different.packagename.subpackage.AccessExamplesSubclass;
import org.apache.commons.lang3.reflect.TypeUtils;
import org.evosuite.Properties;
import org.evosuite.testcase.accessfixture.TableBase;
import org.evosuite.testcase.accessfixture.other.TableSwt;
import org.evosuite.testcase.statements.ArrayStatement;
import org.evosuite.testcase.statements.AssignmentStatement;
import org.evosuite.testcase.statements.ConstructorStatement;
import org.evosuite.testcase.statements.MethodStatement;
import org.evosuite.testcase.statements.StringPrimitiveStatement;
import org.evosuite.testcase.statements.numeric.BooleanPrimitiveStatement;
import org.evosuite.testcase.statements.numeric.IntPrimitiveStatement;
import org.evosuite.testcase.variable.ArrayIndex;
import org.evosuite.testcase.variable.ConstantValue;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.generic.GenericClassFactory;
import org.evosuite.utils.generic.GenericConstructor;
import org.evosuite.utils.generic.GenericMethod;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the exact text of the receiver and return casts of a rendered method call, one test per
 * decision. The compile tests that cover these decisions only check that the code compiles or
 * contains a cast; these tests make a change of spacing, of the cast target or of the decision
 * order visible.
 */
public class CallCastRenderingCharacterisationTest {

    private String originalTargetClass;
    private String originalClassPrefix;

    @BeforeEach
    public void setUp() {
        originalTargetClass = Properties.TARGET_CLASS;
        originalClassPrefix = Properties.CLASS_PREFIX;
        Properties.TARGET_CLASS = CallCastRenderingCharacterisationTest.class.getName();
        Properties.resetTargetClass();
    }

    @AfterEach
    public void tearDown() {
        Properties.TARGET_CLASS = originalTargetClass;
        Properties.CLASS_PREFIX = originalClassPrefix;
        Properties.resetTargetClass();
    }

    public static class Fixtures {
        public static Set<Class> rawClassSet() {
            return Collections.emptySet();
        }

        public static <T extends Number> T pick(T value) {
            return value;
        }

        public static Object anonymous() {
            return new Object() {
            };
        }
    }

    private static String render(TestCase tc) {
        TestCodeVisitor visitor = new TestCodeVisitor();
        tc.accept(visitor);
        return visitor.getCode();
    }

    private static VariableReference construct(TestCase tc, Class<?> clazz, Type ownerType)
            throws NoSuchMethodException {
        return tc.addStatement(new ConstructorStatement(tc,
                new GenericConstructor(clazz.getConstructor(), ownerType), Collections.emptyList()));
    }

    private static VariableReference call(TestCase tc, GenericMethod method, VariableReference callee,
                                          VariableReference... arguments) {
        return tc.addStatement(new MethodStatement(tc, method, callee, Arrays.asList(arguments)));
    }

    // -- receiver casts -----------------------------------------------------------------------

    @Test
    public void receiverForcedRawWhenTheArgumentDoesNotFitTheDeclaredParameterization() throws Exception {
        TestCase tc = new DefaultTestCase();
        VariableReference list = construct(tc, ArrayList.class,
                TypeUtils.parameterize(ArrayList.class, IOException.class));
        VariableReference object = construct(tc, Object.class, Object.class);
        call(tc, new GenericMethod(ArrayList.class.getMethod("add", Object.class), ArrayList.class),
                list, object);
        assertEquals("ArrayList<IOException> arrayList0 = new ArrayList<IOException>();\n"
                + "Object object0 = new Object();\n"
                + "((ArrayList)arrayList0).add(object0);\n", render(tc));
    }

    @Test
    public void receiverForcedRawIsCastThroughObjectWhenTheVariableClassIsUnrelated() throws Exception {
        TestCase tc = new DefaultTestCase();
        VariableReference list = construct(tc, ArrayList.class,
                TypeUtils.parameterize(ArrayList.class, File.class));
        list.setType(TypeUtils.parameterize(List.class, Comparable.class));
        VariableReference value = tc.addStatement(new StringPrimitiveStatement(tc, "value"));
        call(tc, new GenericMethod(ArrayList.class.getMethod("add", Object.class), ArrayList.class),
                list, value);
        assertEquals("ArrayList<File> list0 = new ArrayList<File>();\n"
                + "String string0 = \"value\";\n"
                + "((ArrayList)(Object)list0).add((Object) string0);\n", render(tc));
    }

    @Test
    public void receiverOfABridgeCallIsCastToTheGenericDeclaringClass() throws Exception {
        TestCase tc = new DefaultTestCase();
        VariableReference sub = construct(tc, BridgeMethodCallCompileTest.Sub.class,
                BridgeMethodCallCompileTest.Sub.class);
        VariableReference object = construct(tc, Object.class, Object.class);
        call(tc, new GenericMethod(BridgeMethodCallCompileTest.Sub.class.getMethod("calc", Object.class),
                BridgeMethodCallCompileTest.Sub.class), sub, object);
        assertEquals("BridgeMethodCallCompileTest.Sub bridgeMethodCallCompileTest_Sub0 = new BridgeMethodCallCompileTest.Sub();\n"
                + "Object object0 = new Object();\n"
                + "((BridgeMethodCallCompileTest.Base) bridgeMethodCallCompileTest_Sub0).calc(object0);\n", render(tc));
    }

    @Test
    public void receiverOfAGenericMethodNarrowedByASubclassOverrideIsCastToTheGenericClass()
            throws Exception {
        TestCase tc = new DefaultTestCase();
        VariableReference sub = construct(tc, BridgeMethodCallCompileTest.Sub.class,
                BridgeMethodCallCompileTest.Sub.class);
        VariableReference object = construct(tc, Object.class, Object.class);
        call(tc, new GenericMethod(BridgeMethodCallCompileTest.Base.class.getMethod("calc", Object.class),
                BridgeMethodCallCompileTest.Base.class), sub, object);
        assertEquals("BridgeMethodCallCompileTest.Sub bridgeMethodCallCompileTest_Sub0 = new BridgeMethodCallCompileTest.Sub();\n"
                + "Object object0 = new Object();\n"
                + "((BridgeMethodCallCompileTest.Base) bridgeMethodCallCompileTest_Sub0).calc(object0);\n", render(tc));
    }

    @Test
    public void constantReceiverIsCastToTheDeclaringClass() throws Exception {
        TestCase tc = new DefaultTestCase();
        VariableReference constant = new ConstantValue(tc, GenericClassFactory.get(String.class), "abc");
        call(tc, new GenericMethod(String.class.getMethod("length"), String.class), constant);
        assertEquals("((String)\"abc\").length();\n", render(tc));
    }

    @Test
    public void constantReceiverOfAnUnrelatedClassIsCastThroughObject() throws Exception {
        TestCase tc = new DefaultTestCase();
        VariableReference constant = new ConstantValue(tc, GenericClassFactory.get(Object.class), "abc");
        call(tc, new GenericMethod(String.class.getMethod("length"), String.class), constant);
        assertEquals("((String)(Object)\"abc\").length();\n", render(tc));
    }

    @Test
    public void receiverWhoseClassOverridesTheMethodInaccessiblyIsCastToTheDeclaringClass()
            throws Exception {
        Properties.CLASS_PREFIX = TableBase.class.getPackage().getName();
        TestCase tc = new DefaultTestCase();
        VariableReference table = construct(tc, TableSwt.class, TableSwt.class);
        VariableReference column = tc.addStatement(new StringPrimitiveStatement(tc, "name"));
        VariableReference ascending = tc.addStatement(new BooleanPrimitiveStatement(tc, true));
        call(tc, new GenericMethod(TableBase.class.getDeclaredMethod("setSort", String.class,
                boolean.class), TableBase.class), table, column, ascending);
        assertEquals("TableSwt tableSwt0 = new TableSwt();\n"
                + "String string0 = \"name\";\n"
                + "boolean boolean0 = true;\n"
                + "((TableBase) tableSwt0).setSort(string0, boolean0);\n", render(tc));
    }

    @Test
    public void receiverOfAPackagePrivateMethodIsCastToTheDeclaringClass() throws Exception {
        Properties.CLASS_PREFIX = AccessExamples.class.getPackage().getName();
        TestCase tc = new DefaultTestCase();
        VariableReference receiver = construct(tc, AccessExamplesSubclass.class,
                AccessExamplesSubclass.class);
        call(tc, new GenericMethod(AccessExamples.class.getDeclaredMethod("defaultMethod"),
                AccessExamplesSubclass.class), receiver);
        assertEquals("AccessExamplesSubclass accessExamplesSubclass0 = new AccessExamplesSubclass();\n"
                + "((AccessExamples)accessExamplesSubclass0).defaultMethod();\n", render(tc));
    }

    @Test
    public void receiverWhoseClassLacksTheMethodIsCastThroughObject() throws Exception {
        TestCase tc = new DefaultTestCase();
        VariableReference object = construct(tc, Object.class, Object.class);
        call(tc, new GenericMethod(String.class.getMethod("length"), String.class), object);
        assertEquals("Object object0 = new Object();\n"
                + "((String)(Object)object0).length();\n", render(tc));
    }

    // -- return casts -------------------------------------------------------------------------

    @Test
    public void returnOfAWiderTypeIsCastToTheVariableType() throws Exception {
        TestCase tc = new DefaultTestCase();
        VariableReference list = construct(tc, ArrayList.class, ArrayList.class);
        VariableReference copy = call(tc, new GenericMethod(ArrayList.class.getMethod("clone"),
                ArrayList.class), list);
        copy.setType(ArrayList.class);
        call(tc, new GenericMethod(ArrayList.class.getMethod("size"), ArrayList.class), copy);
        assertEquals("ArrayList arrayList0 = new ArrayList<>();\n"
                + "ArrayList arrayList1 = (ArrayList)arrayList0.clone();\n"
                + "arrayList1.size();\n", render(tc));
    }

    @Test
    public void returnThroughAReceiverCastToARawGenericClassIsCastBack() throws Exception {
        TestCase tc = new DefaultTestCase();
        VariableReference box = construct(tc, BridgeMethodCallCompileTest.StringBox.class,
                BridgeMethodCallCompileTest.StringBox.class);
        VariableReference object = construct(tc, Object.class, Object.class);
        VariableReference swapped = call(tc, new GenericMethod(
                BridgeMethodCallCompileTest.Box.class.getMethod("swap", Object.class),
                BridgeMethodCallCompileTest.StringBox.class), box, object);
        call(tc, new GenericMethod(String.class.getMethod("length"), String.class), swapped);
        assertEquals("BridgeMethodCallCompileTest.StringBox bridgeMethodCallCompileTest_StringBox0 = new BridgeMethodCallCompileTest.StringBox();\n"
                + "Object object0 = new Object();\n"
                + "String string0 = (String)((BridgeMethodCallCompileTest.Box) bridgeMethodCallCompileTest_StringBox0).swap((String) object0);\n"
                + "string0.length();\n", render(tc));
    }

    @Test
    public void returnOfAReceiverRedeclaredWithAnotherParameterizationIsCastRaw() throws Exception {
        TestCase tc = new DefaultTestCase();
        VariableReference list = construct(tc, ArrayList.class,
                TypeUtils.parameterize(ArrayList.class, Object.class));
        list.setType(TypeUtils.parameterize(ArrayList.class, File.class));
        VariableReference iterator = call(tc, new GenericMethod(ArrayList.class.getMethod("listIterator"),
                TypeUtils.parameterize(ArrayList.class, File.class)), list);
        call(tc, new GenericMethod(ListIterator.class.getMethod("hasNext"), ListIterator.class),
                iterator);
        assertEquals("ArrayList<Object> arrayList0 = new ArrayList<Object>();\n"
                + "ListIterator<File> listIterator0 = (ListIterator)arrayList0.listIterator();\n"
                + "listIterator0.hasNext();\n", render(tc));
    }

    @Test
    public void zeroArgumentStaticGenericReturnIsCastRawOnlyWhenUninferable() throws Exception {
        StringBuilder rendered = new StringBuilder();
        for (Type variableType : new Type[] {
                TypeUtils.parameterize(Comparator.class, Object.class),
                TypeUtils.parameterize(Comparator.class,
                        TypeUtils.parameterize(Map.Entry.class, String.class, Integer.class))}) {
            TestCase tc = new DefaultTestCase();
            VariableReference comparator = call(tc, new GenericMethod(
                    Map.Entry.class.getMethod("comparingByValue"), Map.Entry.class), null);
            comparator.setType(variableType);
            call(tc, new GenericMethod(Comparator.class.getMethod("reversed"), Comparator.class),
                    comparator);
            rendered.append(render(tc)).append("--\n");
        }
        assertEquals("Comparator<Object> comparator0 = (Comparator)Map.Entry.comparingByValue();\n"
                + "comparator0.reversed();\n"
                + "--\n"
                + "Comparator comparator0 = Map.Entry.comparingByValue();\n"
                + "comparator0.reversed();\n"
                + "--\n", rendered.toString());
    }

    @Test
    public void returnForAnInconvertibleArrayArgumentIsCastRaw() throws Exception {
        Properties.CLASS_PREFIX = "org.evosuite.testcase";
        TestCase tc = new DefaultTestCase();
        ArrayStatement array = new ArrayStatement(tc, String[].class, 1);
        tc.addStatement(array);
        VariableReference text = tc.addStatement(new StringPrimitiveStatement(tc, "AT"));
        tc.addStatement(new AssignmentStatement(tc, new ArrayIndex(tc, array.getArrayReference(), 0),
                text));
        GenericMethod asList = new GenericMethod(Arrays.class.getMethod("asList", Object[].class),
                Arrays.class);
        asList.setTypeParameters(Collections.singletonList(GenericClassFactory.get(File.class)));
        VariableReference list = call(tc, asList, null, array.getArrayReference());
        call(tc, new GenericMethod(List.class.getMethod("size"), List.class), list);
        assertEquals("String[] stringArray0 = new String[1];\n"
                + "String string0 = \"AT\";\n"
                + "stringArray0[0] = string0;\n"
                + "List<File> list0 = (List)Arrays.asList(stringArray0);\n"
                + "list0.size();\n", render(tc));
    }

    @Test
    public void returnWithAnInconvertibleParameterizationIsCastRaw() throws Exception {
        TestCase tc = new DefaultTestCase();
        VariableReference classes = call(tc, new GenericMethod(
                Fixtures.class.getMethod("rawClassSet"), Fixtures.class), null);
        classes.setType(TypeUtils.parameterize(Set.class,
                TypeUtils.parameterize(Class.class, Object.class)));
        call(tc, new GenericMethod(Set.class.getMethod("size"), Set.class), classes);
        assertEquals("Set<Class<Object>> set0 = (Set)CallCastRenderingCharacterisationTest.Fixtures.rawClassSet();\n"
                + "set0.size();\n", render(tc));
    }

    @Test
    public void genericReturnOfAForcedRawReceiverIsCast() throws Exception {
        TestCase tc = new DefaultTestCase();
        VariableReference map = construct(tc, LinkedHashMap.class,
                TypeUtils.parameterize(LinkedHashMap.class, String.class, String.class));
        VariableReference key = tc.addStatement(new StringPrimitiveStatement(tc, "key"));
        VariableReference object = construct(tc, Object.class, Object.class);
        VariableReference previous = call(tc, new GenericMethod(
                LinkedHashMap.class.getMethod("put", Object.class, Object.class),
                LinkedHashMap.class), map, key, object);
        call(tc, new GenericMethod(Object.class.getMethod("hashCode"), Object.class), previous);
        assertEquals("LinkedHashMap<String, String> linkedHashMap0 = new LinkedHashMap<String, String>();\n"
                + "String string0 = \"key\";\n"
                + "Object object0 = new Object();\n"
                + "Object object1 = (Object)((HashMap)linkedHashMap0).put((Object) string0, object0);\n"
                + "object1.hashCode();\n", render(tc));
    }

    @Test
    public void staticGenericReturnErasedToItsBoundIsCast() throws Exception {
        TestCase tc = new DefaultTestCase();
        VariableReference value = tc.addStatement(new IntPrimitiveStatement(tc, 3));
        GenericMethod pick = new GenericMethod(Fixtures.class.getMethod("pick", Number.class),
                Fixtures.class);
        pick.setTypeParameters(Collections.singletonList(GenericClassFactory.get(Integer.class)));
        VariableReference picked = call(tc, pick, null, value);
        call(tc, new GenericMethod(Integer.class.getMethod("intValue"), Integer.class), picked);
        assertEquals("int int0 = 3;\n"
                + "Integer integer0 = (Integer)CallCastRenderingCharacterisationTest.Fixtures.pick((Integer) int0);\n"
                + "integer0.intValue();\n", render(tc));
    }

    @Test
    public void returnOfAnAnonymousClassIsNotCast() throws Exception {
        TestCase tc = new DefaultTestCase();
        VariableReference anonymous = call(tc, new GenericMethod(
                Fixtures.class.getMethod("anonymous"), Fixtures.class), null);
        anonymous.setType(Fixtures.anonymous().getClass());
        call(tc, new GenericMethod(Object.class.getMethod("hashCode"), Object.class), anonymous);
        assertEquals("CallCastRenderingCharacterisationTest.Fixtures.1 callCastRenderingCharacterisationTest_Fixtures_1_0 = CallCastRenderingCharacterisationTest.Fixtures.anonymous();\n"
                + "callCastRenderingCharacterisationTest_Fixtures_1_0.hashCode();\n", render(tc));
    }

    @Test
    public void unusedReturnIsNeverCast() throws Exception {
        TestCase tc = new DefaultTestCase();
        VariableReference list = construct(tc, ArrayList.class, ArrayList.class);
        VariableReference copy = call(tc, new GenericMethod(ArrayList.class.getMethod("clone"),
                ArrayList.class), list);
        copy.setType(ArrayList.class);
        assertEquals("ArrayList arrayList0 = new ArrayList<>();\n"
                + "arrayList0.clone();\n", render(tc));
    }
}
