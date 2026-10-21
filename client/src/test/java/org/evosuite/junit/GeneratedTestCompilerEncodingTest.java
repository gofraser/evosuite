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
 * License along with EvoSuite. If not, see <http://www.gnu.org/licenses/>.
 */
package org.evosuite.junit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * The compile probe of the repair loop writes the generated source as UTF-8, so javac has to read
 * it as UTF-8 whatever the platform default is. Reading it with the default turned a non-ASCII
 * identifier into an unmappable-character error on a Latin-1 or ASCII platform.
 */
public class GeneratedTestCompilerEncodingTest {

    @TempDir
    Path tmp;

    @Test
    public void printedDiagnosticsCompileReadsTheSourceAsUtf8() throws Exception {
        JavaCompiler real = ToolProvider.getSystemJavaCompiler();
        Charset[] requested = new Charset[1];
        JavaCompiler recording = mock(JavaCompiler.class, invocation -> {
            if (invocation.getMethod().getName().equals("getStandardFileManager")) {
                requested[0] = invocation.getArgument(2);
            }
            return invocation.getMethod().invoke(real, invocation.getArguments());
        });

        Path source = tmp.resolve("Probe.java");
        Files.write(source, "public class Probe { }\n".getBytes(StandardCharsets.UTF_8));
        GeneratedTestCompiler.compileWithPrintedDiagnostics(recording, Collections.singletonList(source.toFile()),
                System.getProperty("java.class.path"));

        assertEquals(StandardCharsets.UTF_8, requested[0]);
    }

    @Test
    public void nonAsciiIdentifierCompiles() throws Exception {
        Path source = tmp.resolve("CaféProbe.java");
        Files.write(source, ("public class CaféProbe { String s = \"ü\"; }\n")
                .getBytes(StandardCharsets.UTF_8));

        GeneratedTestCompiler.CompilerAttemptResult result = GeneratedTestCompiler.compileWithPrintedDiagnostics(
                ToolProvider.getSystemJavaCompiler(), Collections.singletonList(source.toFile()),
                System.getProperty("java.class.path"));

        assertTrue(result.isCompiled(), result.getCompilerOutput());
    }
}
