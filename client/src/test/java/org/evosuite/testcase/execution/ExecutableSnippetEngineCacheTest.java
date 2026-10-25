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
package org.evosuite.testcase.execution;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Type;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutableSnippetEngineCacheTest {

    @Test
    void compiledSnippetCacheIsBounded() throws Throwable {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            ExecutableSnippetEngine engine = new ExecutableSnippetEngine(executor, 2);
            for (int i = 0; i < 4; i++) {
                assertTrue(engine.evaluateAssertion("assertTrue(1 + " + i + " > 0);",
                        Collections.<String, Type>emptyMap(),
                        Collections.<String, Object>emptyMap()));
            }

            Field cacheField = ExecutableSnippetEngine.class.getDeclaredField("cache");
            cacheField.setAccessible(true);
            assertEquals(2, ((Map<?, ?>) cacheField.get(engine)).size());
        } finally {
            executor.shutdownNow();
        }
    }
}
