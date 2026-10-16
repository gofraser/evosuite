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
package org.evosuite.testcase.synthesizer.var;

import org.evosuite.ga.ConstructionFailedException;
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.SyntheticAccessorFixture;
import org.evosuite.testcase.TestFactory;
import org.evosuite.utils.generic.GenericMethod;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A call node in the interprocedural graph may name javac's {@code access$NNN} stub. The
 * synthesizer must not turn it into a statement, because a test calling it does not compile.
 */
public class SyntheticAccessorCallTest {

    @Test
    public void callNodeLookupSkipsAccessorStub() throws Exception {
        Method stub = SyntheticAccessorFixture.define();
        assertNull(OtherVariableWrapper.findSourceCallableMethod(
                stub.getDeclaringClass(), stub.getName(), new Class<?>[] {Object.class}));
    }

    @Test
    public void statementFactoryRefusesAccessorStub() throws Exception {
        Method stub = SyntheticAccessorFixture.define();
        GenericMethod gm = new GenericMethod(stub, stub.getDeclaringClass());
        assertThrows(ConstructionFailedException.class,
                () -> TestFactory.getInstance().addMethod(new DefaultTestCase(), gm, 0, 0));
        DefaultTestCase test = new DefaultTestCase();
        assertThrows(ConstructionFailedException.class,
                () -> TestFactory.getInstance().addMethod(test, gm, 0, 1, true));
        assertEquals(0, test.size());
    }
}
