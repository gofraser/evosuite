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

import org.evosuite.testcase.dmon.DmonEphemeralAssist;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URLClassLoader;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Besides functional mock statements, mocks are created by the lenient default answer of
 * DMoN-promoted mocks and by DMoN's ephemeral injection. None of them may produce a mocked
 * ClassLoader: the first class load through it fails a HotSpot guarantee in
 * ModuleEntry::create_unnamed_module and aborts the JVM. (The mocks created here are never
 * used to load a class, so this test itself is safe.)
 */
public class FunctionalMockClassLoaderReturnTest {

    public interface LoaderSource {
        ClassLoader loader();

        URLClassLoader urlLoader();

        Runnable task();
    }

    @Test
    public void lenientDefaultAnswerDoesNotMockClassLoaders() {
        LoaderSource source = Mockito.mock(LoaderSource.class,
                (org.mockito.stubbing.Answer<?>) FunctionalMockStatement::lenientDefaultAnswer);

        assertNull(source.loader());
        assertNull(source.urlLoader());
        assertNotNull(source.task());
    }

    @Test
    public void ephemeralReturnValueDoesNotMockClassLoaders() throws Exception {
        Method returnValue = DmonEphemeralAssist.class.getDeclaredMethod("createEphemeralReturnValue", Class.class);
        returnValue.setAccessible(true);

        assertNull(returnValue.invoke(null, ClassLoader.class));
        assertNull(returnValue.invoke(null, URLClassLoader.class));
        assertNotNull(returnValue.invoke(null, Runnable.class));
    }

    @Test
    public void ephemeralInjectionRefusesClassLoaderDependencies() throws Exception {
        Method mock = DmonEphemeralAssist.class.getDeclaredMethod("createEphemeralMock", Class.class);
        mock.setAccessible(true);

        // the injection sites treat any exception as "cannot inject" and leave the field alone
        InvocationTargetException thrown = assertThrows(InvocationTargetException.class,
                () -> mock.invoke(null, ClassLoader.class));
        assertNotNull(thrown.getCause());
        assertNotNull(mock.invoke(null, Runnable.class));
    }
}
