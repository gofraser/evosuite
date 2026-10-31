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
package org.evosuite.testcase.statements;

import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.fm.MethodDescriptor;
import org.evosuite.testcase.variable.ArrayIndex;
import org.evosuite.testcase.variable.ArrayReference;
import org.evosuite.testcase.variable.FieldReference;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.generic.GenericClassFactory;
import org.evosuite.utils.generic.GenericConstructor;
import org.evosuite.utils.generic.GenericField;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Chopping a test case drops the stubs of a functional mock that refer to removed statements, but
 * keeps those whose values are fields or array elements of variables that are still defined.
 */
public class FunctionalMockChopStubTest {

    public interface Service {
        String name();
    }

    public static class Holder {
        public String text;
        public static String shared;
    }

    private final TestCase tc = new DefaultTestCase();
    private FunctionalMockStatement mock;

    private void addMock() {
        mock = new FunctionalMockStatement(tc, Service.class, GenericClassFactory.get(Service.class));
        tc.addStatement(mock);
    }

    private void stub(VariableReference value) throws Exception {
        MethodDescriptor descriptor = new MethodDescriptor(Service.class.getMethod("name"),
                GenericClassFactory.get(Service.class));
        descriptor.increaseCounter();
        mock.addMethodStubbing(descriptor, Collections.singletonList(value));
    }

    private VariableReference holder() throws Exception {
        return tc.addStatement(new ConstructorStatement(tc,
                new GenericConstructor(Holder.class.getConstructor(), Holder.class),
                Collections.emptyList()));
    }

    private FieldReference text(VariableReference holder) throws Exception {
        return new FieldReference(tc, new GenericField(Holder.class.getField("text"), Holder.class), holder);
    }

    @Test
    public void fieldOfDefinedVariableSurvivesChopAtTheEnd() throws Exception {
        addMock();
        stub(text(holder()));

        tc.chop(tc.size());

        assertEquals(1, mock.getMockedMethods().size());
        assertEquals(1, mock.getParameterReferences().size());
    }

    @Test
    public void arrayElementOfDefinedVariableSurvivesChopAtTheEnd() throws Exception {
        addMock();
        ArrayReference array = (ArrayReference) tc.addStatement(new ArrayStatement(tc, String[].class, 2));
        stub(new ArrayIndex(tc, array, 0));

        tc.chop(tc.size());

        assertEquals(1, mock.getMockedMethods().size());
    }

    @Test
    public void staticFieldSurvivesChop() throws Exception {
        addMock();
        stub(new FieldReference(tc, new GenericField(Holder.class.getField("shared"), Holder.class)));

        tc.chop(tc.size());

        assertEquals(1, mock.getMockedMethods().size());
    }

    @Test
    public void fieldOfVariableDefinedAfterTheChopPointIsDropped() throws Exception {
        addMock();
        stub(text(holder()));

        tc.chop(1);

        assertTrue(mock.getMockedMethods().isEmpty());
        assertTrue(mock.getParameterReferences().isEmpty());
    }

    @Test
    public void arrayElementOfVariableDefinedAfterTheChopPointIsDropped() throws Exception {
        addMock();
        ArrayReference array = (ArrayReference) tc.addStatement(new ArrayStatement(tc, String[].class, 2));
        stub(new ArrayIndex(tc, array, 0));

        tc.chop(1);

        assertTrue(mock.getMockedMethods().isEmpty());
    }
}
