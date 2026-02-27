/**
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
package org.evosuite.junit.writer;

import org.evosuite.Properties;
import org.evosuite.testcase.execution.ExecutionResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

public class ScaffoldingRegressionTest {

    private final boolean defaultResetStandardStreams = Properties.RESET_STANDARD_STREAMS;
    private final String[] defaultIgnoreThreads = Properties.IGNORE_THREADS;
    private final boolean defaultReplaceCalls = Properties.REPLACE_CALLS;
    private final boolean defaultVirtualFs = Properties.VIRTUAL_FS;
    private final boolean defaultResetStaticFields = Properties.RESET_STATIC_FIELDS;
    private final boolean defaultVirtualNet = Properties.VIRTUAL_NET;
    private final boolean defaultNoRuntimeDependency = Properties.NO_RUNTIME_DEPENDENCY;

    @AfterEach
    public void restoreProperties() {
        Properties.RESET_STANDARD_STREAMS = defaultResetStandardStreams;
        Properties.IGNORE_THREADS = defaultIgnoreThreads;
        Properties.REPLACE_CALLS = defaultReplaceCalls;
        Properties.VIRTUAL_FS = defaultVirtualFs;
        Properties.RESET_STATIC_FIELDS = defaultResetStaticFields;
        Properties.VIRTUAL_NET = defaultVirtualNet;
        Properties.NO_RUNTIME_DEPENDENCY = defaultNoRuntimeDependency;
    }

    @Test
    public void testIgnoreThreadsAreQuotedInGeneratedCode() {
        configureDefaults();
        Properties.IGNORE_THREADS = new String[]{"AWT-EventQueue-0", "pool-1-thread-"};

        String code = new Scaffolding().getBeforeAndAfterMethods("Bug3Test", false, Collections.emptyList());
        Assertions.assertTrue(code.contains(", \"AWT-EventQueue-0\""));
        Assertions.assertTrue(code.contains(", \"pool-1-thread-\""));
        Assertions.assertFalse(code.contains(", AWT-EventQueue-0"));
    }

    @Test
    public void testScaffoldingImportsIncludeStreamsAndDebugGraphics() {
        configureDefaults();
        Properties.RESET_STANDARD_STREAMS = true;

        List<ExecutionResult> results = Collections.emptyList();
        String code = Scaffolding.getScaffoldingFileContent("Bug4Test", results, false);
        Assertions.assertTrue(code.contains("import java.io.PrintStream;"));
        Assertions.assertTrue(code.contains("import javax.swing.DebugGraphics;"));
    }

    private static void configureDefaults() {
        Properties.RESET_STANDARD_STREAMS = false;
        Properties.IGNORE_THREADS = new String[]{};
        Properties.REPLACE_CALLS = false;
        Properties.VIRTUAL_FS = false;
        Properties.RESET_STATIC_FIELDS = false;
        Properties.VIRTUAL_NET = false;
        Properties.NO_RUNTIME_DEPENDENCY = false;
    }
}
