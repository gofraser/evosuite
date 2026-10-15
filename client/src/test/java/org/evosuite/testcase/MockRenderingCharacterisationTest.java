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
import org.evosuite.testcase.execution.TestCaseExecutor;
import org.evosuite.testcase.fm.MethodDescriptor;
import org.evosuite.testcase.statements.FunctionalMockForAbstractClassStatement;
import org.evosuite.testcase.statements.FunctionalMockStatement;
import org.evosuite.testcase.statements.MethodStatement;
import org.evosuite.testcase.statements.Statement;
import org.evosuite.testcase.statements.StringPrimitiveStatement;
import org.evosuite.testcase.statements.numeric.BooleanPrimitiveStatement;
import org.evosuite.testcase.statements.numeric.DoublePrimitiveStatement;
import org.evosuite.testcase.statements.numeric.IntPrimitiveStatement;
import org.evosuite.testcase.statements.numeric.LongPrimitiveStatement;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.generic.GenericClassFactory;
import org.evosuite.utils.generic.GenericMethod;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Characterisation of the text generated for tests with functional mocks, before rendering is
 * made free of side effects on the model.
 *
 * <p>The mocks here are "already repaired": their stubs are complete, so the
 * repair that rendering performs today has nothing left to do. Their rendering must stay
 * byte-identical when {@code getCode}/{@code toCode} become pure.
 */
public class MockRenderingCharacterisationTest {

    private String originalTargetClass;

    @BeforeEach
    public void setUp() {
        Properties.getInstance().resetToDefaults();
        originalTargetClass = Properties.TARGET_CLASS;
        Properties.TARGET_CLASS = MockRenderingCharacterisationTest.class.getName();
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

        boolean enabled();

        long id();

        double ratio();

        Other other();
    }

    public interface Other {
        String label();
    }

    public abstract static class Base {
        public abstract String value();

        public int concrete() {
            return 1;
        }
    }

    public interface Typed<T> {
        T get();
    }

    public static class User {
        public static String use(Service first, Service second) {
            return first.name() + second.name();
        }

        public static String useBase(Base base) {
            return base.value();
        }

        public static Object useTyped(Typed<String> typed) {
            return typed.get();
        }
    }

    private static MethodDescriptor stub(FunctionalMockStatement mock, Class<?> type, String method,
                                         int calls, VariableReference... values) throws Exception {
        MethodDescriptor descriptor = new MethodDescriptor(type.getMethod(method),
                GenericClassFactory.get(type));
        for (int i = 0; i < calls; i++) {
            descriptor.increaseCounter();
        }
        mock.addMethodStubbing(descriptor, Arrays.asList(values));
        return descriptor;
    }

    private static VariableReference call(TestCase tc, Class<?> owner, String name, Class<?>[] types,
                                          VariableReference... args) throws Exception {
        return tc.addStatement(new MethodStatement(tc, new GenericMethod(owner.getMethod(name, types), owner),
                null, Arrays.asList(args)));
    }

    // ------------------------------------------------------------------ scenarios

    private static TestCase stubbedInterfaceMock() throws Exception {
        TestCase tc = new DefaultTestCase();
        VariableReference name = tc.addStatement(new StringPrimitiveStatement(tc, "alpha"));
        VariableReference count = tc.addStatement(new IntPrimitiveStatement(tc, 3));
        VariableReference enabled = tc.addStatement(new BooleanPrimitiveStatement(tc, true));
        VariableReference id = tc.addStatement(new LongPrimitiveStatement(tc, 5L));
        VariableReference ratio = tc.addStatement(new DoublePrimitiveStatement(tc, 1.5));
        FunctionalMockStatement mock = new FunctionalMockStatement(tc, Service.class,
                GenericClassFactory.get(Service.class));
        VariableReference ref = tc.addStatement(mock);
        stub(mock, Service.class, "name", 1, name);
        stub(mock, Service.class, "count", 1, count);
        stub(mock, Service.class, "enabled", 1, enabled);
        stub(mock, Service.class, "id", 1, id);
        stub(mock, Service.class, "ratio", 1, ratio);
        // the same mock is passed twice and is therefore used by one statement more than once
        call(tc, User.class, "use", new Class<?>[]{Service.class, Service.class}, ref, ref);
        return tc;
    }

    private static TestCase successiveValuesMock() throws Exception {
        TestCase tc = new DefaultTestCase();
        VariableReference first = tc.addStatement(new StringPrimitiveStatement(tc, "first"));
        VariableReference second = tc.addStatement(new StringPrimitiveStatement(tc, "second"));
        FunctionalMockStatement mock = new FunctionalMockStatement(tc, Service.class,
                GenericClassFactory.get(Service.class));
        VariableReference ref = tc.addStatement(mock);
        stub(mock, Service.class, "name", 2, first, second);
        call(tc, User.class, "use", new Class<?>[]{Service.class, Service.class}, ref, ref);
        return tc;
    }

    private static TestCase abstractClassMock() throws Exception {
        TestCase tc = new DefaultTestCase();
        VariableReference value = tc.addStatement(new StringPrimitiveStatement(tc, "v"));
        FunctionalMockForAbstractClassStatement mock = new FunctionalMockForAbstractClassStatement(tc,
                Base.class, GenericClassFactory.get(Base.class));
        VariableReference ref = tc.addStatement(mock);
        stub(mock, Base.class, "value", 1, value);
        call(tc, User.class, "useBase", new Class<?>[]{Base.class}, ref);
        return tc;
    }

    private static TestCase mockReturningMock() throws Exception {
        TestCase tc = new DefaultTestCase();
        VariableReference label = tc.addStatement(new StringPrimitiveStatement(tc, "lbl"));
        FunctionalMockStatement inner = new FunctionalMockStatement(tc, Other.class,
                GenericClassFactory.get(Other.class));
        VariableReference innerRef = tc.addStatement(inner);
        stub(inner, Other.class, "label", 1, label);
        FunctionalMockStatement outer = new FunctionalMockStatement(tc, Service.class,
                GenericClassFactory.get(Service.class));
        VariableReference outerRef = tc.addStatement(outer);
        stub(outer, Service.class, "other", 1, innerRef);
        call(tc, User.class, "use", new Class<?>[]{Service.class, Service.class}, outerRef, outerRef);
        return tc;
    }

    private static TestCase parameterizedMock() throws Exception {
        TestCase tc = new DefaultTestCase();
        VariableReference value = tc.addStatement(new StringPrimitiveStatement(tc, "typed"));
        Type type = TypeUtils.parameterize(Typed.class, String.class);
        FunctionalMockStatement mock = new FunctionalMockStatement(tc, type, GenericClassFactory.get(type));
        VariableReference ref = tc.addStatement(mock);
        MethodDescriptor descriptor = new MethodDescriptor(Typed.class.getMethod("get"),
                GenericClassFactory.get(type));
        descriptor.increaseCounter();
        mock.addMethodStubbing(descriptor, Collections.singletonList(value));
        call(tc, User.class, "useTyped", new Class<?>[]{Typed.class}, ref);
        return tc;
    }

    private static TestCase narrowedStubValue() throws Exception {
        TestCase tc = new DefaultTestCase();
        VariableReference id = tc.addStatement(new IntPrimitiveStatement(tc, 7));
        FunctionalMockStatement mock = new FunctionalMockStatement(tc, Service.class,
                GenericClassFactory.get(Service.class));
        VariableReference ref = tc.addStatement(mock);
        stub(mock, Service.class, "id", 1, id);
        call(tc, User.class, "use", new Class<?>[]{Service.class, Service.class}, ref, ref);
        return tc;
    }


    /** A mock that was executed, so that its listener knows which methods the SUT called. */
    private static TestCase executedUnrepairedMock() throws Exception {
        TestCase tc = new DefaultTestCase();
        FunctionalMockStatement mock = new FunctionalMockStatement(tc, Service.class,
                GenericClassFactory.get(Service.class));
        VariableReference ref = tc.addStatement(mock);
        call(tc, User.class, "use", new Class<?>[]{Service.class, Service.class}, ref, ref);
        TestCaseExecutor.runTest(tc);
        return tc;
    }

    /** The explicit repair that the search performs before it evaluates a test. */
    private static TestCase executedRepairedMock() throws Exception {
        TestCase tc = executedUnrepairedMock();
        FunctionalMockStatement mock = (FunctionalMockStatement) tc.getStatement(0);
        mock.updateMockedMethods();
        mock.fillWithNullRefs();
        assertFalse(mock.doesNeedToUpdateInputs(), "precondition: the mock is repaired");
        return tc;
    }

    // ------------------------------------------------------------------ helpers

    private static String render(TestCase tc) {
        return tc.toCode().replace("\r\n", "\n");
    }

    private static String lines(String... lines) {
        return String.join("\n", lines) + "\n";
    }

    /** Everything a mock keeps about its stubs, plus the size of the test. */
    private static List<String> modelState(TestCase tc) {
        List<String> state = new ArrayList<>();
        state.add("size=" + tc.size());
        for (int i = 0; i < tc.size(); i++) {
            Statement statement = tc.getStatement(i);
            if (!(statement instanceof FunctionalMockStatement)) {
                continue;
            }
            FunctionalMockStatement mock = (FunctionalMockStatement) statement;
            StringBuilder sb = new StringBuilder("mock@" + i + " needsUpdate=" + mock.doesNeedToUpdateInputs());
            for (MethodDescriptor descriptor : mock.getMockedMethods()) {
                sb.append(" stub[").append(descriptor.getID()).append(" x").append(descriptor.getCounter()).append("]");
            }
            for (VariableReference reference : mock.getParameterReferences()) {
                sb.append(" ref[").append(System.identityHashCode(reference)).append("]");
            }
            state.add(sb.toString());
        }
        return state;
    }

    private static final String MOCK_SETTINGS = "withSettings().stubOnly().invocationListeners("
            + "new MockInvocationLimit()).defaultAnswer(new ViolatedAssumptionAnswer())";
    private static final String S = "MockRenderingCharacterisationTest.Service";
    private static final String SV = "mockRenderingCharacterisationTest_Service0";
    private static final String USE = "MockRenderingCharacterisationTest.User.use(" + SV + ", " + SV + ");";

    private static final String STUBBED_INTERFACE = lines(
            "String string0 = \"alpha\";",
            "int int0 = 3;",
            "boolean boolean0 = true;",
            "long long0 = 5L;",
            "double double0 = 1.5;",
            S + " " + SV + " = mock(" + S + ".class, " + MOCK_SETTINGS + ");",
            "doReturn(string0).when(" + SV + ").name();",
            "doReturn(int0).when(" + SV + ").count();",
            "doReturn(boolean0).when(" + SV + ").enabled();",
            "doReturn(long0).when(" + SV + ").id();",
            "doReturn(double0).when(" + SV + ").ratio();",
            USE);

    private static final String SUCCESSIVE_VALUES = lines(
            "String string0 = \"first\";",
            "String string1 = \"second\";",
            S + " " + SV + " = mock(" + S + ".class, " + MOCK_SETTINGS + ");",
            "doReturn(string0, string1).when(" + SV + ").name();",
            USE);

    private static final String ABSTRACT_CLASS = lines(
            "String string0 = \"v\";",
            "MockRenderingCharacterisationTest.Base mockRenderingCharacterisationTest_Base0 = mock("
                    + "MockRenderingCharacterisationTest.Base.class, withSettings().stubOnly()"
                    + ".invocationListeners(new MockInvocationLimit()).defaultAnswer(CALLS_REAL_METHODS));",
            "doReturn(string0).when(mockRenderingCharacterisationTest_Base0).value();",
            "MockRenderingCharacterisationTest.User.useBase(mockRenderingCharacterisationTest_Base0);");

    private static final String MOCK_RETURNING_MOCK = lines(
            "String string0 = \"lbl\";",
            "MockRenderingCharacterisationTest.Other mockRenderingCharacterisationTest_Other0 = mock("
                    + "MockRenderingCharacterisationTest.Other.class, " + MOCK_SETTINGS + ");",
            "doReturn(string0).when(mockRenderingCharacterisationTest_Other0).label();",
            S + " " + SV + " = mock(" + S + ".class, " + MOCK_SETTINGS + ");",
            "doReturn(mockRenderingCharacterisationTest_Other0).when(" + SV + ").other();",
            USE);

    private static final String PARAMETERIZED = lines(
            "String string0 = \"typed\";",
            "MockRenderingCharacterisationTest.Typed<String> mockRenderingCharacterisationTest_Typed0 = "
                    + "(MockRenderingCharacterisationTest.Typed<String>) mock("
                    + "MockRenderingCharacterisationTest.Typed.class, " + MOCK_SETTINGS + ");",
            "doReturn(string0).when(mockRenderingCharacterisationTest_Typed0).get();",
            "MockRenderingCharacterisationTest.User.useTyped(mockRenderingCharacterisationTest_Typed0);");

    private static final String NARROWED_STUB = lines(
            "int int0 = 7;",
            S + " " + SV + " = mock(" + S + ".class, " + MOCK_SETTINGS + ");",
            "doReturn((long)int0).when(" + SV + ").id();",
            USE);

    /** Two pending calls of name(); both are answered with null, cast to Object. */
    private static final String NULL_FILLED_STUBS = lines(
            S + " " + SV + " = mock(" + S + ".class, " + MOCK_SETTINGS + ");",
            "doReturn((Object) null, (Object) null).when(" + SV + ").name();",
            USE);

    // ------------------------------------------------------------------ golden text

    @Test
    public void primitiveAndBooleanStubsOfAnInterfaceMockReusedInOneCall() throws Exception {
        assertEquals(STUBBED_INTERFACE, render(stubbedInterfaceMock()));
    }

    @Test
    public void successiveValuesOfOneMethod() throws Exception {
        assertEquals(SUCCESSIVE_VALUES, render(successiveValuesMock()));
    }

    @Test
    public void abstractClassMockCallsRealMethods() throws Exception {
        assertEquals(ABSTRACT_CLASS, render(abstractClassMock()));
    }

    @Test
    public void mockReturningAnotherMock() throws Exception {
        assertEquals(MOCK_RETURNING_MOCK, render(mockReturningMock()));
    }

    @Test
    public void parameterizedMockIsCastToItsDeclaredType() throws Exception {
        assertEquals(PARAMETERIZED, render(parameterizedMock()));
    }

    @Test
    public void narrowerStubValueIsCastToTheReturnType() throws Exception {
        assertEquals(NARROWED_STUB, render(narrowedStubValue()));
    }

    @Test
    public void executedAndExplicitlyRepairedMockRendersNullFilledStubs() throws Exception {
        assertEquals(NULL_FILLED_STUBS, render(executedRepairedMock()));
    }

    // ------------------------------------------------------------------ rendering twice

    @Test
    public void renderingRepairedTestsTwiceGivesTheSameTextAndLeavesTheModelAlone() throws Exception {
        Map<String, TestCase> scenarios = new LinkedHashMap<>();
        scenarios.put("interface", stubbedInterfaceMock());
        scenarios.put("successive", successiveValuesMock());
        scenarios.put("abstract", abstractClassMock());
        scenarios.put("mockInMock", mockReturningMock());
        scenarios.put("parameterized", parameterizedMock());
        scenarios.put("narrowed", narrowedStubValue());
        scenarios.put("executedRepaired", executedRepairedMock());

        for (Map.Entry<String, TestCase> entry : scenarios.entrySet()) {
            TestCase tc = entry.getValue();
            List<String> before = modelState(tc);

            String first = render(tc);
            List<String> afterFirst = modelState(tc);
            String second = render(tc);
            List<String> afterSecond = modelState(tc);

            assertEquals(first, second, entry.getKey() + ": second rendering differs");
            assertEquals(before, afterFirst, entry.getKey() + ": rendering changed the test");
            assertEquals(before, afterSecond, entry.getKey() + ": second rendering changed the test");
        }
    }

    @Test
    public void renderingASingleRepairedStatementIsRepeatable() throws Exception {
        TestCase tc = executedRepairedMock();
        List<String> before = modelState(tc);

        String first = tc.getStatement(0).getCode();
        String second = tc.getStatement(0).getCode();

        assertEquals(first, second);
        assertEquals(before, modelState(tc));
    }

    // ------------------------------------------------------------------ unrepaired tests

    /**
     * An executed mock whose stubs were never completed is rendered with null answers. The text
     * is what a pure rendering has to produce from its read-only view of the pending stubs.
     */
    @Test
    public void unrepairedExecutedMockIsRenderedWithNullFilledStubs() throws Exception {
        TestCase tc = executedUnrepairedMock();
        assertTrue(((FunctionalMockStatement) tc.getStatement(0)).doesNeedToUpdateInputs(),
                "precondition: the mock still needs its inputs");

        String first = render(tc);
        String second = render(tc);

        assertEquals(NULL_FILLED_STUBS, first);
        assertEquals(first, second);
    }

    /** Rendering today completes the pending stubs of the mock it renders. */
    @Test
    @Disabled("package 1: rendering still mutates")
    public void renderingAnUnrepairedTestLeavesTheMockUntouched() throws Exception {
        TestCase tc = executedUnrepairedMock();
        List<String> before = modelState(tc);
        assertTrue(before.get(1).contains("needsUpdate=true"), before.toString());

        render(tc);

        assertEquals(before, modelState(tc), "toCode() must not repair the mocks it renders");
    }

    /** The same for the code of one statement (AbstractStatement#getCode). */
    @Test
    @Disabled("package 1: rendering still mutates")
    public void renderingAnUnrepairedStatementLeavesTheMockUntouched() throws Exception {
        TestCase tc = executedUnrepairedMock();
        List<String> before = modelState(tc);

        tc.getStatement(0).getCode();

        assertEquals(before, modelState(tc), "getCode() must not repair the mock it renders");
    }
}
