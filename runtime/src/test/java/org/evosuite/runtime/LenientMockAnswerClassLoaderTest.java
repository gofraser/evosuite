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
package org.evosuite.runtime;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.net.URLClassLoader;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * A generated test whose DMoN mock answers with {@link LenientMockAnswer} must not hand the
 * code under test a mocked ClassLoader: the mock's constructor never ran, and the first class
 * load through it fails a HotSpot guarantee in ModuleEntry::create_unnamed_module, aborting
 * the test JVM.
 */
public class LenientMockAnswerClassLoaderTest {

    public interface LoaderSource {
        ClassLoader loader();

        URLClassLoader urlLoader();

        Runnable task();
    }

    @Test
    public void classLoaderReturnTypesAnswerNull() {
        LoaderSource source = Mockito.mock(LoaderSource.class, new LenientMockAnswer());

        assertNull(source.loader());
        assertNull(source.urlLoader());
        // other non-final types are still answered with a lenient mock
        assertNotNull(source.task());
    }
}
