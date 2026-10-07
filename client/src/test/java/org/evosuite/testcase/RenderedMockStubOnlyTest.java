package org.evosuite.testcase;

import org.evosuite.runtime.ViolatedAssumptionAnswer;
import org.evosuite.testcase.fm.MethodDescriptor;
import org.evosuite.testcase.statements.FunctionalMockStatement;
import org.evosuite.testcase.statements.numeric.IntPrimitiveStatement;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.generic.GenericClassFactory;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.withSettings;

/**
 * Mocks in a rendered test must be stubOnly: otherwise Mockito records every call, and a SUT loop
 * over a stubbed mock exhausts the heap of the JVM that runs the JUnit check.
 */
public class RenderedMockStubOnlyTest {

    @Test
    public void renderedMockIsStubOnly() throws Exception {
        DefaultTestCase tc = new DefaultTestCase();
        FunctionalMockStatement mock = new FunctionalMockStatement(
                tc, Comparable.class, GenericClassFactory.get(Comparable.class));
        tc.addStatement(mock);
        VariableReference zero = tc.addStatement(new IntPrimitiveStatement(tc, 0));
        Method compareTo = Comparable.class.getMethod("compareTo", Object.class);
        MethodDescriptor descriptor =
                new MethodDescriptor(compareTo, GenericClassFactory.get(Comparable.class));
        descriptor.increaseCounter();
        mock.addMethodStubbing(descriptor, Collections.singletonList(zero));

        TestCodeVisitor visitor = new TestCodeVisitor();
        tc.accept(visitor);
        String code = visitor.getCode();

        assertTrue(code.contains("withSettings().stubOnly().invocationListeners(new MockInvocationLimit())"
                + ".defaultAnswer(new ViolatedAssumptionAnswer())"), code);
        assertFalse(code.contains(".class, new ViolatedAssumptionAnswer())"), code);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void stubOnlyMockDoesNotRetainInvocations() {
        Comparable<Object> plain = mock(Comparable.class, new ViolatedAssumptionAnswer());
        Comparable<Object> stubOnly = mock(Comparable.class,
                withSettings().stubOnly().defaultAnswer(new ViolatedAssumptionAnswer()));
        doReturn(0).when(plain).compareTo(org.mockito.ArgumentMatchers.any());
        doReturn(0).when(stubOnly).compareTo(org.mockito.ArgumentMatchers.any());
        for (int i = 0; i < 1000; i++) {
            plain.compareTo(null);
            stubOnly.compareTo(null);
        }
        assertEquals(1000, mockingDetails(plain).getInvocations().size());
        assertEquals(0, mockingDetails(stubOnly).getInvocations().size());
    }
}
