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

import org.evosuite.Properties;
import org.evosuite.TestGenerationContext;
import org.evosuite.instrumentation.InstrumentingClassLoader;
import org.junit.jupiter.api.Test;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.lang.reflect.Field;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutableSnippetEngineClassLoaderIsolationTest {

    @Test
    void executeStatementUsesDistinctCompiledSnippetPerSutClassLoader() throws Throwable {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "System Java compiler is required for this test");

        Path tmp = Files.createTempDirectory("evosuite-snippet-loader");
        Path pkgDir = tmp.resolve("snippet/fixture");
        Files.createDirectories(pkgDir);

        Path sourceFile = pkgDir.resolve("SharedType.java");
        String source = "package snippet.fixture;\n"
                + "public class SharedType {\n"
                + "  private final int value;\n"
                + "  public SharedType(int value) { this.value = value; }\n"
                + "  public int getValue() { return value; }\n"
                + "}\n";
        Files.write(sourceFile, source.getBytes(StandardCharsets.UTF_8));
        int javacResult = compiler.run(null, null, null, "-d", tmp.toString(), sourceFile.toString());
        assertEquals(0, javacResult, "Fixture class compilation failed");

        String previousCp = Properties.CP;
        Field classLoaderField = TestGenerationContext.class.getDeclaredField("classLoader");
        classLoaderField.setAccessible(true);
        InstrumentingClassLoader previousLoader =
                (InstrumentingClassLoader) classLoaderField.get(TestGenerationContext.getInstance());

        try {
            Properties.CP = tmp.toString();

            InstrumentingClassLoader loaderA = new InstrumentingClassLoader();
            InstrumentingClassLoader loaderB = new InstrumentingClassLoader();

            Class<?> typeA = loaderA.loadClass("snippet.fixture.SharedType");
            Class<?> typeB = loaderB.loadClass("snippet.fixture.SharedType");
            assertTrue(typeA != typeB, "Expected same FQN to be loaded by different classloaders");

            Object valueA = typeA.getConstructor(int.class).newInstance(11);
            Object valueB = typeB.getConstructor(int.class).newInstance(22);

            ExecutableSnippetEngine engine = ExecutableSnippetEngine.INSTANCE;

            Map<String, Type> typesA = new LinkedHashMap<>();
            Map<String, Object> valuesA = new LinkedHashMap<>();
            typesA.put("x", typeA);
            valuesA.put("x", valueA);

            Map<String, Type> typesB = new LinkedHashMap<>();
            Map<String, Object> valuesB = new LinkedHashMap<>();
            typesB.put("x", typeB);
            valuesB.put("x", valueB);

            classLoaderField.set(TestGenerationContext.getInstance(), loaderA);
            ExecutableSnippetEngine.StatementResult resultA =
                    engine.executeStatement("int ignored = 0;", typesA, valuesA, "x");
            assertSame(typeA, resultA.getReturnValue().getClass());

            classLoaderField.set(TestGenerationContext.getInstance(), loaderB);
            ExecutableSnippetEngine.StatementResult resultB =
                    engine.executeStatement("int ignored = 0;", typesB, valuesB, "x");
            assertSame(typeB, resultB.getReturnValue().getClass());
        } finally {
            classLoaderField.set(TestGenerationContext.getInstance(), previousLoader);
            Properties.CP = previousCp;
        }
    }
}
