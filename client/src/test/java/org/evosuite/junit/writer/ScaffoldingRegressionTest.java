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
import org.evosuite.testcase.DefaultTestCase;
import org.evosuite.testcase.execution.ExecutionResult;
import org.evosuite.testcase.execution.TestCaseExecutor;
import org.evosuite.testcase.execution.reset.ClassReInitializer;
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
        ClassReInitializer.resetSingleton();
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

    private static final String SAVE = "standardStreams = org.evosuite.runtime.StandardStreams.save();";
    private static final String RESTORE = "org.evosuite.runtime.StandardStreams.restore(standardStreams);";

    @Test
    public void testStandardStreamsAreRestoredWithoutResetStandardStreams() {
        configureDefaults();

        String code = new Scaffolding().getBeforeAndAfterMethods("Bug5Test", false, Collections.emptyList());
        Assertions.assertTrue(code.contains(SAVE), code);
        Assertions.assertTrue(code.contains(RESTORE), code);
        Assertions.assertFalse(code.contains("DebugGraphics"), code);
    }

    @Test
    public void testStandardStreamsAreRestoredAfterTheClassReset() {
        configureDefaults();
        Properties.RESET_STATIC_FIELDS = true;

        String code = new Scaffolding().getBeforeAndAfterMethods("Bug6Test", false, Collections.emptyList());
        int reset = code.indexOf("resetClasses(); ");
        int restore = code.indexOf(RESTORE);
        Assertions.assertTrue(reset >= 0 && restore > reset, code);
    }

    @Test
    public void testStandardStreamsAreRestoredEvenWhenAResetThrows() {
        configureDefaults();
        Properties.RESET_STATIC_FIELDS = true;

        String all = new Scaffolding().getBeforeAndAfterMethods("Bug8Test", false, Collections.emptyList());
        String code = all.substring(all.indexOf("doneWithTestCase"));
        int tryBlock = code.indexOf("try { ");
        int reset = code.indexOf("resetClasses(); ");
        int finallyBlock = code.indexOf("\n    } finally { \n");
        int restore = code.indexOf(RESTORE);
        int sandbox = code.indexOf("Sandbox.doneWithExecutingSUTCode();");
        Assertions.assertTrue(tryBlock >= 0 && reset > tryBlock && finallyBlock > reset
                && restore > finallyBlock && sandbox > restore, code);
    }

    @Test
    public void testEachCleanupStepRunsEvenWhenAnEarlierOneThrows() {
        configureDefaults();
        boolean headless = Properties.HEADLESS_MODE;
        Properties.HEADLESS_MODE = true;
        Properties.RESET_STATIC_FIELDS = true;
        try {
            String all = new Scaffolding().getBeforeAndAfterMethods("Bug11Test", false, Collections.emptyList());
            String code = all.substring(all.indexOf("doneWithTestCase"));
            String expected = "    } finally { \n"
                    + "      try { \n"
                    + "        " + RESTORE + " \n"
                    + "      } finally { \n"
                    + "        try { \n"
                    + "          org.evosuite.runtime.sandbox.Sandbox.doneWithExecutingSUTCode(); \n"
                    + "        } finally { \n"
                    + "          try { \n"
                    + "            org.evosuite.runtime.agent.InstrumentingAgent.deactivate(); \n"
                    + "          } finally { \n"
                    + "            org.evosuite.runtime.GuiSupport.restoreHeadlessMode(); \n"
                    + "          } \n"
                    + "        } \n"
                    + "      } \n"
                    + "    } \n"
                    + "  } \n";
            Assertions.assertTrue(code.contains(expected), code);
        } finally {
            Properties.HEADLESS_MODE = headless;
        }
    }

    @Test
    public void testEachResetStepRunsEvenWhenAnEarlierOneThrows() {
        configureDefaults();
        Properties.REPLACE_CALLS = true;
        Properties.RESET_STATIC_FIELDS = true;

        String all = new Scaffolding().getBeforeAndAfterMethods("Bug14Test", false, Collections.emptyList());
        String code = all.substring(all.indexOf("doneWithTestCase"));
        Assertions.assertTrue(code.contains("    try { \n"
                + "      try { \n"
                + "        threadStopper.killAndJoinClientThreads(); \n"
                + "      } finally { \n"
                + "        try { \n"
                + "          org.evosuite.runtime.jvm.ShutdownHookHandler.getInstance().safeExecuteAddedHooks(); \n"
                + "        } finally { \n"
                + "          try { \n"
                + "            org.evosuite.runtime.classhandling.JDKClassResetter.reset(); \n"
                + "          } finally { \n"
                + "            resetClasses(); \n"
                + "          } \n"
                + "        } \n"
                + "      } \n"
                + "    } finally { \n"), code);
    }

    @Test
    public void testOnlyTheStepsInUseAreChained() {
        configureDefaults();
        boolean headless = Properties.HEADLESS_MODE;
        Properties.HEADLESS_MODE = true;
        try {
            String all = new Scaffolding().getBeforeAndAfterMethods("Bug12Test", false, Collections.emptyList());
            String code = all.substring(all.indexOf("doneWithTestCase"));
            Assertions.assertTrue(code.contains("    } finally { \n"
                    + "      try { \n"
                    + "        " + RESTORE + " \n"
                    + "      } finally { \n"
                    + "        try { \n"
                    + "          org.evosuite.runtime.agent.InstrumentingAgent.deactivate(); \n"
                    + "        } finally { \n"
                    + "          org.evosuite.runtime.GuiSupport.restoreHeadlessMode(); \n"
                    + "        } \n"
                    + "      } \n"
                    + "    } \n"), code);
            Assertions.assertFalse(code.contains("Sandbox.doneWithExecutingSUTCode"), code);
        } finally {
            Properties.HEADLESS_MODE = headless;
        }
    }

    @Test
    public void testEachClassCleanupStepRunsEvenWhenAnEarlierOneThrows() {
        configureDefaults();
        Properties.REPLACE_CALLS = true;
        Properties.RESET_STATIC_FIELDS = true;

        String all = new Scaffolding().getBeforeAndAfterMethods("Bug13Test", true, Collections.emptyList());
        String code = all.substring(all.indexOf("clearEvoSuiteFramework"));
        Assertions.assertTrue(code.contains("    try { \n"
                + "      try { \n"
                + "        Sandbox.resetDefaultSecurityManager(); \n"
                + "      } finally { \n"
                + "        executor.shutdownNow(); \n"
                + "      } \n"
                + "    } finally { \n"
                + "      org.evosuite.runtime.StandardStreams.restore(classStandardStreams); \n"
                + "    } \n"), code);
    }

    @Test
    public void testStandardStreamsOfTheClassAreSavedBeforeAndRestoredAfterTheClass() {
        configureDefaults();
        Properties.REPLACE_CALLS = true;
        Properties.RESET_STATIC_FIELDS = true;

        String code = new Scaffolding().getBeforeAndAfterMethods("Bug9Test", false, Collections.emptyList());
        int save = code.indexOf("classStandardStreams = org.evosuite.runtime.StandardStreams.save();");
        int init = code.indexOf("initializeSecurityManagerForSUT");
        int restore = code.indexOf("org.evosuite.runtime.StandardStreams.restore(classStandardStreams);");
        int afterClass = code.indexOf("clearEvoSuiteFramework");
        Assertions.assertTrue(save > 0 && init > save, code);
        Assertions.assertTrue(afterClass > init && restore > afterClass, code);
    }

    @Test
    public void testStandardStreamsOfTheClassAreRestoredWhenOnlyTheAgentIsUsed() {
        configureDefaults();
        Properties.REPLACE_CALLS = true;

        String code = new Scaffolding().getBeforeAndAfterMethods("Bug10Test", false, Collections.emptyList());
        Assertions.assertTrue(code.contains("clearEvoSuiteFramework"), code);
        Assertions.assertTrue(code.contains("classStandardStreams"), code);
    }

    @Test
    public void testNoRuntimeDependencyEmitsNoStreamFields() {
        configureDefaults();
        Properties.NO_RUNTIME_DEPENDENCY = true;

        String code = new Scaffolding().getBeforeAndAfterMethods("Bug11Test", false, Collections.emptyList());
        Assertions.assertFalse(code.contains("standardStreams"), code);
        Assertions.assertFalse(code.contains("StandardStreams"), code);
        Assertions.assertFalse(code.contains("systemOut"), code);
    }

    @Test
    public void testScaffoldingHeaderIsDeterministic() {
        configureDefaults();

        String first = Scaffolding.getScaffoldingFileContent(
                "DeterministicTest", Collections.emptyList(), false);
        String second = Scaffolding.getScaffoldingFileContent(
                "DeterministicTest", Collections.emptyList(), false);

        Assertions.assertEquals(first, second);
        Assertions.assertTrue(first.contains(
                "Generation timestamp omitted for deterministic output"));
    }

    @Test
    public void testExplicitResetClassSnapshotIgnoresLaterDiscoveries() {
        configureDefaults();
        Properties.RESET_STATIC_FIELDS = true;
        Scaffolding.ReplaySnapshot snapshot = new Scaffolding.ReplaySnapshot(
                Collections.singletonList("example.StructuralClass"),
                Collections.singletonList("example.InitializedClass"),
                Collections.singletonList("example.MockedType"));

        String code = new Scaffolding().getBeforeAndAfterMethods(
                "ReplayTest", false, Collections.emptyList(), snapshot);

        Assertions.assertTrue(code.contains("\"example.StructuralClass\""));
        Assertions.assertFalse(code.contains("example.PostProcessingClass"));
        Assertions.assertTrue(code.contains("\"example.InitializedClass\""));
        Assertions.assertTrue(code.contains("Class.forName(\"example.MockedType\""));
    }

    @Test
    public void testScaffoldingFileDeclaresExecutorForTimeouts() {
        configureDefaults();

        // A test that timed out is wrapped in executor.submit(...) by TestSuiteWriter even
        // when no security exception occurred, so the scaffolding file must declare the field.
        DefaultTestCase test = new DefaultTestCase();
        ExecutionResult timedOut = new ExecutionResult(test);
        timedOut.reportNewThrownException(test.size(), new TestCaseExecutor.TimeoutExceeded());
        List<ExecutionResult> results = Collections.singletonList(timedOut);

        String code = Scaffolding.getScaffoldingFileContent("TimeoutTest", results, false);
        Assertions.assertTrue(code.contains("protected static ExecutorService executor;"), code);
        Assertions.assertTrue(code.contains("import java.util.concurrent.ExecutorService;"), code);
        Assertions.assertTrue(code.contains("executor = Executors.newCachedThreadPool();"), code);
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
