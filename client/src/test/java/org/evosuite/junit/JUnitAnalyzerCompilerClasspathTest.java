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
package org.evosuite.junit;

import org.evosuite.Properties;
import org.evosuite.classpath.ClassPathHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class JUnitAnalyzerCompilerClasspathTest {

    @TempDir
    File tmp;

    private String savedCp;

    @BeforeEach
    public void saveClasspath() {
        savedCp = Properties.CP;
    }

    @AfterEach
    public void restoreClasspath() {
        ClassPathHandler.resetSingleton();
        Properties.CP = savedCp;
    }

    @Test
    public void classpathIsReusedWhileItsInputsAreUnchanged() {
        File first = new File(tmp, "first");
        assertTrue(first.mkdir());
        ClassPathHandler.getInstance().changeTargetClassPath(new String[]{first.getAbsolutePath()});

        String classpath = GeneratedTestCompiler.compilerClasspathForGeneratedTests();

        assertSame(classpath, GeneratedTestCompiler.compilerClasspathForGeneratedTests());
    }

    @Test
    public void classpathFollowsAChangedTargetClasspath() {
        File first = new File(tmp, "first");
        File second = new File(tmp, "second");
        assertTrue(first.mkdir());
        assertTrue(second.mkdir());
        ClassPathHandler.getInstance().changeTargetClassPath(new String[]{first.getAbsolutePath()});
        List<String> before = entries(GeneratedTestCompiler.compilerClasspathForGeneratedTests());

        ClassPathHandler.getInstance().changeTargetClassPath(new String[]{second.getAbsolutePath()});
        List<String> after = entries(GeneratedTestCompiler.compilerClasspathForGeneratedTests());

        assertTrue(before.contains(first.getAbsolutePath()));
        assertTrue(after.contains(second.getAbsolutePath()));
        assertFalse(after.contains(first.getAbsolutePath()));
    }

    private static List<String> entries(String classpath) {
        return Arrays.asList(classpath.split(File.pathSeparator));
    }
}
