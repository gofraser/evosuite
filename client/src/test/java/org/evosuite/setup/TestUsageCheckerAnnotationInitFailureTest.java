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
package org.evosuite.setup;

import org.evosuite.Properties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reading a class's annotations initialises the enums its annotation members default to. When
 * that static initialiser of subject-under-test code throws, the resulting
 * ExceptionInInitializerError must not escape {@link TestUsageChecker#canUse(Class)} and abort
 * the whole generation.
 *
 * <p>The fixture is compiled at run time: as a compiled test class it would be initialised by
 * the test runner's own discovery scan before the test starts.
 */
public class TestUsageCheckerAnnotationInitFailureTest {

    private boolean originalUseDeprecated;

    @BeforeEach
    public void disallowDeprecated() {
        originalUseDeprecated = Properties.USE_DEPRECATED;
        Properties.USE_DEPRECATED = false;
    }

    @AfterEach
    public void restore() {
        Properties.USE_DEPRECATED = originalUseDeprecated;
    }

    private static void write(Path dir, String name, String source) throws IOException {
        Files.write(dir.resolve(name + ".java"), source.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void classWhoseAnnotationDefaultCannotInitialiseIsRejectedNotThrown(@TempDir Path dir)
            throws Exception {
        write(dir, "Broken", "public enum Broken { FIRST; static { if (System.nanoTime() != 0L) "
                + "throw new IllegalStateException(\"subject static initialiser failed\"); } }");
        write(dir, "Marker", "@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)\n"
                + "public @interface Marker { Broken value() default Broken.FIRST; }");
        write(dir, "Annotated", "@Marker public class Annotated { }");

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        Assertions.assertNotNull(compiler);
        int status = compiler.run(null, null, null, "-d", dir.toString(),
                dir.resolve("Broken.java").toString(), dir.resolve("Marker.java").toString(),
                dir.resolve("Annotated.java").toString());
        Assertions.assertEquals(0, status);

        try (URLClassLoader loader = new URLClassLoader(new URL[]{dir.toUri().toURL()},
                getClass().getClassLoader())) {
            Class<?> annotated = Class.forName("Annotated", false, loader);
            Assertions.assertFalse(TestUsageChecker.canUse(annotated));
            // The enum is now permanently broken; a second look must behave the same.
            Assertions.assertFalse(TestUsageChecker.canUse(annotated));
        }
    }
}
