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
import org.evosuite.testcase.fm.MethodDescriptor;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.testcase.variable.VariableReferenceImpl;
import org.evosuite.utils.generic.GenericClassFactory;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A copy of a functional mock must carry the mock's flags. The abstract-class mock has its own
 * copy of {@code copy}, which has drifted from the parent's and drops them; a parsed abstract mock
 * then loses {@code populatedFromParser} on every clone.
 */
public class FunctionalMockForAbstractClassCopyTest {

    public abstract static class Service {
        public abstract String value();
    }

    @Test
    public void plainMockCopyKeepsItsFlags() throws Exception {
        DefaultTestCase test = new DefaultTestCase();
        VariableReference ref = new VariableReferenceImpl(test, Service.class);
        FunctionalMockStatement mock = new FunctionalMockStatement(
                test, ref, GenericClassFactory.get(Service.class));
        assertFlagsSurviveCopy(test, mock);
    }

    @Test
    public void abstractClassMockCopyKeepsItsFlags() throws Exception {
        DefaultTestCase test = new DefaultTestCase();
        VariableReference ref = new VariableReferenceImpl(test, Service.class);
        FunctionalMockForAbstractClassStatement mock = new FunctionalMockForAbstractClassStatement(
                test, ref, GenericClassFactory.get(Service.class));
        assertFlagsSurviveCopy(test, mock);
    }

    private static void assertFlagsSurviveCopy(DefaultTestCase test, FunctionalMockStatement mock)
            throws Exception {
        test.addStatement(mock);
        // as the test parser does: one stub, no values
        mock.addMethodStubbing(new MethodDescriptor(Service.class.getMethod("value"),
                GenericClassFactory.get(Service.class)), Collections.emptyList());
        mock.setUseLenientDefaultAnswer(true);
        mock.setDmonPromotion(true);
        assertTrue(mock.populatedFromParser, "precondition: the stub marks the mock as parsed");

        FunctionalMockStatement copy = (FunctionalMockStatement) mock.copy(new DefaultTestCase(), 0);

        assertAll(
                () -> assertTrue(copy.populatedFromParser, "populatedFromParser was dropped by copy"),
                () -> assertTrue(copy.isUseLenientDefaultAnswer(), "useLenientDefaultAnswer was dropped by copy"),
                () -> assertTrue(copy.isDmonPromotion(), "isDmonPromotion was dropped by copy"));
    }
}
