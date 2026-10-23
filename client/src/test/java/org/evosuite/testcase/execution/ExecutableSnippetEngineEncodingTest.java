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

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ExecutableSnippetEngineEncodingTest {

    /**
     * The snippet sources are written as UTF-8, so the compiler has to be told to read them
     * that way: on a JVM whose default charset is not UTF-8, non-ASCII literals are otherwise
     * misread.
     */
    @Test
    void compilerReadsSourcesAsUtf8() throws Exception {
        Method buildCompilerArguments = ExecutableSnippetEngine.class.getDeclaredMethod(
                "buildCompilerArguments", String.class, Path.class);
        buildCompilerArguments.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<String> args = (List<String>) buildCompilerArguments.invoke(
                ExecutableSnippetEngine.INSTANCE, "cp", Paths.get("Snippet.java"));

        int index = args.indexOf("-encoding");
        assertEquals("UTF-8", args.get(index + 1), args.toString());
    }
}
