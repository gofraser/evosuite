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

import org.evosuite.Properties;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.execution.TestCaseExecutor;
import org.evosuite.testcase.fm.MethodDescriptor;
import org.evosuite.testcase.statements.FunctionalMockForAbstractClassStatement;
import org.evosuite.testcase.statements.FunctionalMockStatement;
import org.evosuite.testcase.statements.MethodStatement;
import org.evosuite.testcase.statements.Statement;
import org.evosuite.testcase.statements.StringPrimitiveStatement;
import org.evosuite.testcase.statements.numeric.IntPrimitiveStatement;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.testparser.TestParser;
import org.evosuite.utils.generic.GenericClassFactory;
import org.evosuite.utils.generic.GenericMethod;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Characterisation of how a functional mock keeps its stubs: the order in which they are
 * declared and updated, and what copying, serializing and parsing a test keep of them.
 */
public class FunctionalMockStubCharacterisationTest {

    private String originalTargetClass;

    @BeforeEach
    public void setUp() {
        Properties.getInstance().resetToDefaults();
        originalTargetClass = Properties.TARGET_CLASS;
        Properties.TARGET_CLASS = FunctionalMockStubCharacterisationTest.class.getName();
        Properties.resetTargetClass();
    }

    @AfterEach
    public void tearDown() {
        Properties.TARGET_CLASS = originalTargetClass;
        Properties.resetTargetClass();
        Properties.getInstance().resetToDefaults();
    }

    public interface Service {
        String name();

        int count();

        String label(int index);
    }

    public abstract static class Base {
        public abstract String value();
    }

    public static class User {
        public static String use(Service service) {
            return service.name() + service.name() + service.count() + service.label(1);
        }

        public static String useBase(Base base) {
            return base.value();
        }
    }

    private static void stub(FunctionalMockStatement mock, String method, Class<?>[] parameters,
                             int calls, VariableReference... values) throws Exception {
        MethodDescriptor descriptor = new MethodDescriptor(Service.class.getMethod(method, parameters),
                GenericClassFactory.get(Service.class));
        for (int i = 0; i < calls; i++) {
            descriptor.increaseCounter();
        }
        mock.addMethodStubbing(descriptor, Arrays.asList(values));
    }

    private static void use(TestCase tc, VariableReference mock) throws Exception {
        tc.addStatement(new MethodStatement(tc,
                new GenericMethod(User.class.getMethod("use", Service.class), User.class),
                null, Arrays.asList(mock)));
    }

    /** Stubs declared in the order label, name, count, which is not the sorted order. */
    private static TestCase unsortedStubs() throws Exception {
        TestCase tc = new DefaultTestCase();
        VariableReference label = tc.addStatement(new StringPrimitiveStatement(tc, "lbl"));
        VariableReference first = tc.addStatement(new StringPrimitiveStatement(tc, "first"));
        VariableReference second = tc.addStatement(new StringPrimitiveStatement(tc, "second"));
        VariableReference count = tc.addStatement(new IntPrimitiveStatement(tc, 3));
        FunctionalMockStatement mock = new FunctionalMockStatement(tc, Service.class,
                GenericClassFactory.get(Service.class));
        VariableReference ref = tc.addStatement(mock);
        stub(mock, "label", new Class<?>[]{int.class}, 1, label);
        stub(mock, "name", new Class<?>[0], 2, first, second);
        stub(mock, "count", new Class<?>[0], 1, count);
        use(tc, ref);
        return tc;
    }

    private static FunctionalMockStatement mockOf(TestCase tc) {
        for (Statement statement : tc) {
            if (statement instanceof FunctionalMockStatement) {
                return (FunctionalMockStatement) statement;
            }
        }
        throw new AssertionError("no mock in\n" + tc.toCode());
    }

    private static List<String> stubIds(FunctionalMockStatement mock) {
        List<String> ids = new ArrayList<>();
        for (MethodDescriptor descriptor : mock.getMockedMethods()) {
            ids.add(descriptor.getMethodName() + " x" + descriptor.getCounter());
        }
        return ids;
    }

    private static List<Integer> valuePositions(FunctionalMockStatement mock) {
        List<Integer> positions = new ArrayList<>();
        for (VariableReference value : mock.getParameterReferences()) {
            positions.add(value.getStPosition());
        }
        return positions;
    }

    private static String render(TestCase tc) {
        return tc.toCode().replace("\r\n", "\n");
    }

    private static final String MOCK_SETTINGS = "withSettings().stubOnly().invocationListeners("
            + "new MockInvocationLimit()).defaultAnswer(new ViolatedAssumptionAnswer())";
    private static final String S = "FunctionalMockStubCharacterisationTest.Service";
    private static final String SV = "functionalMockStubCharacterisationTest_Service0";

    private static String lines(String... lines) {
        return String.join("\n", lines) + "\n";
    }

    private static final String UNSORTED = lines(
            "String string0 = \"lbl\";",
            "String string1 = \"first\";",
            "String string2 = \"second\";",
            "int int0 = 3;",
            S + " " + SV + " = mock(" + S + ".class, " + MOCK_SETTINGS + ");",
            "doReturn(string0).when(" + SV + ").label(anyInt());",
            "doReturn(string1, string2).when(" + SV + ").name();",
            "doReturn(int0).when(" + SV + ").count();",
            "FunctionalMockStubCharacterisationTest.User.use(" + SV + ");");

    /** After an execution the stubs follow the sorted order of the observed calls. */
    private static final String SORTED = lines(
            "String string0 = \"lbl\";",
            "String string1 = \"first\";",
            "String string2 = \"second\";",
            "int int0 = 3;",
            S + " " + SV + " = mock(" + S + ".class, " + MOCK_SETTINGS + ");",
            "doReturn(int0).when(" + SV + ").count();",
            "doReturn(string0).when(" + SV + ").label(anyInt());",
            "doReturn(string1, string2).when(" + SV + ").name();",
            "FunctionalMockStubCharacterisationTest.User.use(" + SV + ");");

    @Test
    public void declaredStubsAreRenderedInTheOrderTheyWereAdded() throws Exception {
        TestCase tc = unsortedStubs();
        assertEquals(UNSORTED, render(tc));
        assertEquals(Arrays.asList("label x1", "name x2", "count x1"), stubIds(mockOf(tc)));
        assertEquals(Arrays.asList(0, 1, 2, 3), valuePositions(mockOf(tc)));
    }

    @Test
    public void updatingFromAnExecutionKeepsTheValuesOfEachMethodInSortedOrder() throws Exception {
        TestCase tc = unsortedStubs();
        ExecutionResult result = TestCaseExecutor.runTest(tc);
        assertFalse(result.hasTimeout());
        FunctionalMockStatement mock = mockOf(tc);
        // every observed call is stubbed, but in another order
        assertTrue(mock.doesNeedToUpdateInputs());
        assertEquals(SORTED, render(tc), "the pending stubs are rendered as the update leaves them");
        assertEquals(Arrays.asList("label x1", "name x2", "count x1"), stubIds(mock));

        assertTrue(mock.updateMockedMethods().isEmpty());

        assertEquals(Arrays.asList("count x1", "label x1", "name x2"), stubIds(mock));
        assertEquals(Arrays.asList(3, 0, 1, 2), valuePositions(mock));
        assertEquals(SORTED, render(tc));
    }

    @Test
    public void anExecutionWithFewerCallsDropsTheLastValues() throws Exception {
        TestCase tc = new DefaultTestCase();
        VariableReference first = tc.addStatement(new StringPrimitiveStatement(tc, "first"));
        VariableReference second = tc.addStatement(new StringPrimitiveStatement(tc, "second"));
        VariableReference third = tc.addStatement(new StringPrimitiveStatement(tc, "third"));
        FunctionalMockStatement mock = new FunctionalMockStatement(tc, Service.class,
                GenericClassFactory.get(Service.class));
        VariableReference ref = tc.addStatement(mock);
        stub(mock, "name", new Class<?>[0], 3, first, second, third);
        use(tc, ref);
        TestCaseExecutor.runTest(tc);

        List<?> missing = mock.updateMockedMethods();
        // count() and label(int) were called but not stubbed
        assertEquals(2, missing.size());
        mock.fillWithNullRefs();

        assertEquals(Arrays.asList("count x1", "label x1", "name x2"), stubIds(mock));
        List<VariableReference> values = mock.getParameterReferences();
        assertEquals(4, values.size());
        assertSame(first, values.get(2));
        assertSame(second, values.get(3));
    }

    @Test
    public void copyKeepsTheStubsAndRebindsTheirValues() throws Exception {
        TestCase tc = unsortedStubs();
        TestCase clone = tc.clone();
        FunctionalMockStatement original = mockOf(tc);
        FunctionalMockStatement copy = mockOf(clone);

        assertEquals(render(tc), render(clone));
        assertEquals(stubIds(original), stubIds(copy));
        assertEquals(valuePositions(original), valuePositions(copy));
        for (int i = 0; i < copy.getParameterReferences().size(); i++) {
            assertSame(clone.getStatement(copy.getParameterReferences().get(i).getStPosition()).getReturnValue(),
                    copy.getParameterReferences().get(i));
        }
        for (int i = 0; i < copy.getMockedMethods().size(); i++) {
            assertNotSame(original.getMockedMethods().get(i), copy.getMockedMethods().get(i));
        }
        assertTrue(original.same(copy));

        // the copy's stubs are its own
        VariableReference extra = clone.addStatement(new IntPrimitiveStatement(clone, 9), 0);
        MethodDescriptor descriptor = new MethodDescriptor(Service.class.getMethod("count"),
                GenericClassFactory.get(Service.class));
        descriptor.increaseCounter();
        copy.addMethodStubbing(descriptor, Arrays.asList(extra));
        assertEquals(UNSORTED, render(tc));
        assertEquals(Arrays.asList("label x1", "name x2", "count x1"), stubIds(original));
        assertEquals(4, original.getParameterReferences().size());
    }

    @Test
    public void copyOfAnAbstractClassMockKeepsItsKindAndStubs() throws Exception {
        TestCase tc = new DefaultTestCase();
        VariableReference value = tc.addStatement(new StringPrimitiveStatement(tc, "v"));
        FunctionalMockForAbstractClassStatement mock = new FunctionalMockForAbstractClassStatement(tc,
                Base.class, GenericClassFactory.get(Base.class));
        VariableReference ref = tc.addStatement(mock);
        MethodDescriptor descriptor = new MethodDescriptor(Base.class.getMethod("value"),
                GenericClassFactory.get(Base.class));
        descriptor.increaseCounter();
        mock.addMethodStubbing(descriptor, Arrays.asList(value));
        tc.addStatement(new MethodStatement(tc,
                new GenericMethod(User.class.getMethod("useBase", Base.class), User.class),
                null, Arrays.asList(ref)));

        TestCase clone = tc.clone();
        FunctionalMockStatement copy = mockOf(clone);
        assertSame(FunctionalMockForAbstractClassStatement.class, copy.getClass());
        assertEquals(render(tc), render(clone));
        assertEquals(1, copy.getMockedMethods().size());
        assertSame(clone.getStatement(0).getReturnValue(), copy.getParameterReferences().get(0));
    }

    @Test
    public void stubsWithDifferentMethodsAreNotEqual() throws Exception {
        TestCase tc = unsortedStubs();
        TestCase other = new DefaultTestCase();
        VariableReference label = other.addStatement(new StringPrimitiveStatement(other, "lbl"));
        VariableReference first = other.addStatement(new StringPrimitiveStatement(other, "first"));
        VariableReference second = other.addStatement(new StringPrimitiveStatement(other, "second"));
        VariableReference count = other.addStatement(new IntPrimitiveStatement(other, 3));
        FunctionalMockStatement mock = new FunctionalMockStatement(other, Service.class,
                GenericClassFactory.get(Service.class));
        other.addStatement(mock);
        stub(mock, "label", new Class<?>[]{int.class}, 1, label);
        stub(mock, "name", new Class<?>[0], 2, first, second);
        // same values, but the last one stubs another method
        stub(mock, "label", new Class<?>[]{int.class}, 1, count);
        assertFalse(mockOf(tc).same(mock));
    }

    @Test
    public void serializationKeepsTheStubs() throws Exception {
        TestCase tc = unsortedStubs();
        TestChromosome chromosome = new TestChromosome();
        chromosome.setTestCase(tc);

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(chromosome);
        }
        TestChromosome read;
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            read = (TestChromosome) in.readObject();
        }

        TestCase restored = read.getTestCase();
        assertEquals(UNSORTED, render(restored));
        assertEquals(stubIds(mockOf(tc)), stubIds(mockOf(restored)));
        assertEquals(valuePositions(mockOf(tc)), valuePositions(mockOf(restored)));
        assertTrue(TestCaseExecutor.runTest(restored).noThrownExceptions());
    }

    @Test
    public void parsingTheRenderedTestGivesTheSameStubs() throws Exception {
        TestCase tc = unsortedStubs();
        String code = render(tc);
        List<String> imports = Arrays.asList(
                "import static org.mockito.Mockito.*;",
                "import org.evosuite.runtime.MockInvocationLimit;",
                "import org.evosuite.runtime.ViolatedAssumptionAnswer;",
                "import " + FunctionalMockStubCharacterisationTest.class.getName() + ";");
        TestCase parsed = new TestParser(getClass().getClassLoader())
                .parseTestMethodBody(code, imports).getTestCase();

        assertEquals(code, render(parsed));
        assertEquals(stubIds(mockOf(tc)), stubIds(mockOf(parsed)));
        assertEquals(valuePositions(mockOf(tc)), valuePositions(mockOf(parsed)));
        assertTrue(TestCaseExecutor.runTest(parsed).noThrownExceptions());
    }

    @Test
    public void aSecondStubOfTheSameMethodDecidesWhatTheMockReturns() throws Exception {
        TestCase tc = new DefaultTestCase();
        VariableReference first = tc.addStatement(new StringPrimitiveStatement(tc, "first"));
        VariableReference second = tc.addStatement(new StringPrimitiveStatement(tc, "second"));
        FunctionalMockStatement mock = new FunctionalMockStatement(tc, Service.class,
                GenericClassFactory.get(Service.class));
        VariableReference ref = tc.addStatement(mock);
        stub(mock, "name", new Class<?>[0], 1, first);
        stub(mock, "name", new Class<?>[0], 2, second);
        VariableReference result = tc.addStatement(new MethodStatement(tc,
                new GenericMethod(Service.class.getMethod("name"), Service.class), ref,
                new ArrayList<>()));

        org.evosuite.testcase.execution.Scope scope = new org.evosuite.testcase.execution.Scope();
        for (Statement statement : tc) {
            assertEquals(null, statement.execute(scope, System.out));
        }
        assertEquals("second", result.getObject(scope));
    }
}
