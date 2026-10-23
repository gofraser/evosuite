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
package org.evosuite.junit.writer;

import org.evosuite.Properties;
import org.evosuite.symbolic.TestCaseBuilder;
import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.execution.ExecutionTraceImpl;
import org.evosuite.testcase.execution.TestCaseExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A replayed suite takes its imports from the requirements captured before post-processing,
 * while the test bodies follow the results of the final execution. A test that only times out in
 * the final execution is written with the thread wrapper and the sandbox, so the file must
 * import what they use.
 */
public class ScaffoldingReplayImportsTest {

    private final Properties.OutputFormat format = Properties.TEST_FORMAT;
    private final boolean scaffolding = Properties.TEST_SCAFFOLDING;
    private final boolean extensionMode = Properties.TEST_EXTENSION_MODE;
    private final boolean noRuntime = Properties.NO_RUNTIME_DEPENDENCY;
    private final Properties.OutputGranularity granularity = Properties.OUTPUT_GRANULARITY;
    private final Properties.TestNamingStrategy naming = Properties.TEST_NAMING_STRATEGY;
    private final boolean replaceCalls = Properties.REPLACE_CALLS;
    private final boolean virtualFs = Properties.VIRTUAL_FS;
    private final boolean resetStatic = Properties.RESET_STATIC_FIELDS;
    private final boolean virtualNet = Properties.VIRTUAL_NET;
    private final boolean replaceGui = Properties.REPLACE_GUI;
    private final String[] ignoreThreads = Properties.IGNORE_THREADS;
    private final String classPrefix = Properties.CLASS_PREFIX;

    @AfterEach
    public void restore() {
        Properties.TEST_FORMAT = format;
        Properties.TEST_SCAFFOLDING = scaffolding;
        Properties.TEST_EXTENSION_MODE = extensionMode;
        Properties.NO_RUNTIME_DEPENDENCY = noRuntime;
        Properties.OUTPUT_GRANULARITY = granularity;
        Properties.TEST_NAMING_STRATEGY = naming;
        Properties.REPLACE_CALLS = replaceCalls;
        Properties.VIRTUAL_FS = virtualFs;
        Properties.RESET_STATIC_FIELDS = resetStatic;
        Properties.VIRTUAL_NET = virtualNet;
        Properties.REPLACE_GUI = replaceGui;
        Properties.IGNORE_THREADS = ignoreThreads;
        Properties.CLASS_PREFIX = classPrefix;
    }

    /** Writer that hands back a prepared result instead of executing the test. */
    private static final class PreparedWriter extends TestSuiteWriter {
        private ExecutionResult prepared;

        @Override
        protected ExecutionResult runTest(TestCase test) {
            return prepared;
        }
    }

    @Test
    public void timeoutOnlyInTheFinalExecutionStillImportsTheThreadWrapper() throws Exception {
        Properties.TEST_FORMAT = Properties.OutputFormat.JUNIT4;
        Properties.OUTPUT_GRANULARITY = Properties.OutputGranularity.MERGED;
        Properties.TEST_NAMING_STRATEGY = Properties.TestNamingStrategy.NUMBERED;
        Properties.REPLACE_CALLS = false;
        Properties.VIRTUAL_FS = false;
        Properties.RESET_STATIC_FIELDS = false;
        Properties.VIRTUAL_NET = false;
        Properties.REPLACE_GUI = false;
        Properties.IGNORE_THREADS = new String[]{};
        Properties.CLASS_PREFIX = "";
        Properties.NO_RUNTIME_DEPENDENCY = false;
        Properties.TEST_SCAFFOLDING = false;
        Properties.TEST_EXTENSION_MODE = false;

        TestCaseBuilder builder = new TestCaseBuilder();
        builder.appendIntPrimitive(1);
        TestCase test = builder.getDefaultTestCase();
        ExecutionResult observed = new ExecutionResult(test);
        observed.setTrace(new ExecutionTraceImpl());
        PreparedWriter writer = new PreparedWriter();
        writer.insertAllTests(Collections.singletonList(test));
        writer.setReplayScaffoldingSnapshot(
                Scaffolding.captureReplaySnapshot(Collections.singletonList(test),
                        Collections.singletonList(observed)));

        // the final execution times out
        ExecutionResult final_ = new ExecutionResult(test);
        final_.setTrace(new ExecutionTraceImpl());
        final_.reportNewThrownException(test.size(), new TestCaseExecutor.TimeoutExceeded());
        writer.prepared = final_;

        Path dir = Files.createTempDirectory("replay-imports");
        List<File> files = writer.writeTestSuite("ReplayImportsTest", dir.toString(), Collections.emptyList());
        String source = new String(Files.readAllBytes(files.get(0).toPath()), StandardCharsets.UTF_8);

        assertTrue(source.contains("Future<"), "the test must be written with the thread wrapper:\n" + source);
        assertTrue(source.contains("Sandbox."), "the scaffolding must use the sandbox:\n" + source);
        for (String imported : new String[]{"java.util.concurrent.Future", "java.util.concurrent.ExecutorService",
                "java.util.concurrent.Executors", "org.evosuite.runtime.sandbox.Sandbox",
                "org.junit.AfterClass"}) {
            assertTrue(source.contains("import " + imported + ";"),
                    "the wrapper and sandbox are used, so " + imported + " must be imported:\n" + source);
        }
    }
}
