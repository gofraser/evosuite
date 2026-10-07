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

import com.examples.with.different.packagename.fm.ExampleWithFinalMethod;
import org.evosuite.runtime.instrumentation.RemoveFinalClassAdapter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Type;
import org.mockito.Mockito;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A method that was final in the original bytecode is intercepted during the search (the
 * instrumenting class loader strips the modifier) but not in the generated test's JVM. The
 * mock's default answer must therefore run the real method during the search, so that the
 * exception origin and values the search observed are the ones the test sees on replay.
 */
public class FunctionalMockFinalMethodDefaultAnswerTest {

    @BeforeEach
    public void setUp() {
        RemoveFinalClassAdapter.reset();
    }

    @AfterEach
    public void tearDown() {
        RemoveFinalClassAdapter.reset();
    }

    @Test
    public void originallyFinalMethodRunsRealCodeOnMock() throws Exception {
        // Stand in for what InstrumentingClassLoader does: it strips the modifier and records
        // the method. (The test classpath uses the inline mock maker, which cannot mock classes
        // from a foreign loader, so the class is used as is.)
        Method recorded = ExampleWithFinalMethod.class.getMethod("isOverridable", String.class);
        Method other = ExampleWithFinalMethod.class.getMethod("isEnabled", String.class);
        RemoveFinalClassAdapter.recordFinalMethod(ExampleWithFinalMethod.class.getName(),
                "isOverridable", Type.getMethodDescriptor(recorded));

        ExampleWithFinalMethod mock = Mockito.mock(ExampleWithFinalMethod.class,
                Mockito.withSettings().stubOnly().defaultAnswer(
                        FunctionalMockStatement.callRealMethodIfOriginallyFinal(Mockito.RETURNS_DEFAULTS)));

        // Recorded as originally final: real code runs, as it does in an unstripped test JVM.
        assertEquals(true, mock.isOverridable("x"));
        assertEquals(false, RemoveFinalClassAdapter.isFinalMethod(other));
    }
}
