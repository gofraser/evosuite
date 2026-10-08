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
import org.evosuite.runtime.LoopCounter;
import org.evosuite.runtime.RuntimeSettings;
import org.evosuite.runtime.mock.MockFramework;
import org.evosuite.testsupport.StreamWrappingFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.runner.JUnitCore;
import org.junit.runner.Result;

import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.PrintStream;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Code under test that wraps System.out on construction (a redirecting logger) must not see its
 * wrappers pile up over the tests of a generated suite: every wrapped println fans out into all
 * earlier wrappers. The generated scaffolding therefore hands the streams back after each test,
 * whatever the setting of reset_standard_streams.
 */
public class ScaffoldingStandardStreamsTest {

    private final boolean defaultResetStandardStreams = Properties.RESET_STANDARD_STREAMS;
    private final boolean defaultReplaceCalls = Properties.REPLACE_CALLS;
    private final boolean defaultVirtualFs = Properties.VIRTUAL_FS;
    private final boolean defaultResetStaticFields = Properties.RESET_STATIC_FIELDS;
    private final boolean defaultVirtualNet = Properties.VIRTUAL_NET;
    private final boolean defaultNoRuntimeDependency = Properties.NO_RUNTIME_DEPENDENCY;
    private final String[] defaultIgnoreThreads = Properties.IGNORE_THREADS;
    private final Properties.OutputFormat defaultTestFormat = Properties.TEST_FORMAT;
    private PrintStream out;
    private PrintStream err;
    /** The statics that the generated scaffolding sets when it runs in this JVM. */
    private Map<Field, Object> runtimeSettings;
    private boolean mockFrameworkEnabled;

    @BeforeEach
    public void setUp() throws Exception {
        out = System.out;
        Properties.TEST_FORMAT = Properties.OutputFormat.JUNIT4;
        err = System.err;
        Properties.RESET_STANDARD_STREAMS = false;
        Properties.REPLACE_CALLS = false;
        Properties.VIRTUAL_FS = false;
        Properties.RESET_STATIC_FIELDS = false;
        Properties.VIRTUAL_NET = false;
        Properties.NO_RUNTIME_DEPENDENCY = false;
        Properties.IGNORE_THREADS = new String[]{};
        runtimeSettings = snapshotRuntimeSettings();
        mockFrameworkEnabled = MockFramework.isEnabled();
    }

    @AfterEach
    public void tearDown() throws Exception {
        // The scaffolding's @BeforeClass/@Before write RuntimeSettings, switch the mock
        // framework and reset the loop counter; later tests in this JVM must not see that.
        for (Map.Entry<Field, Object> e : runtimeSettings.entrySet()) {
            e.getKey().set(null, e.getValue());
        }
        if (mockFrameworkEnabled) {
            MockFramework.enable();
        } else {
            MockFramework.disable();
        }
        LoopCounter.getInstance().reset();
        System.setOut(out);
        System.setErr(err);
        Properties.TEST_FORMAT = defaultTestFormat;
        Properties.RESET_STANDARD_STREAMS = defaultResetStandardStreams;
        Properties.REPLACE_CALLS = defaultReplaceCalls;
        Properties.VIRTUAL_FS = defaultVirtualFs;
        Properties.RESET_STATIC_FIELDS = defaultResetStaticFields;
        Properties.VIRTUAL_NET = defaultVirtualNet;
        Properties.NO_RUNTIME_DEPENDENCY = defaultNoRuntimeDependency;
        Properties.IGNORE_THREADS = defaultIgnoreThreads;
    }

    private static Map<Field, Object> snapshotRuntimeSettings() throws Exception {
        Map<Field, Object> values = new LinkedHashMap<>();
        for (Field f : RuntimeSettings.class.getFields()) {
            if (Modifier.isStatic(f.getModifiers()) && !Modifier.isFinal(f.getModifiers())) {
                values.put(f, f.get(null));
            }
        }
        return values;
    }

    @Test
    public void scaffoldingRestoresStreamsWithoutResetStandardStreams() throws Exception {
        runSuite("", "", true, 1);
    }

    @Test
    public void scaffoldingRestoresStreamsWithResetStandardStreams() throws Exception {
        Properties.RESET_STANDARD_STREAMS = true;
        runSuite("", "", true, 1);
    }

    /** A wrapper installed while the class is initialised is gone once the class is done. */
    @Test
    public void scaffoldingRestoresStreamsWrappedByTheBeforeClass() throws Exception {
        String fixture = StreamWrappingFixture.class.getName();
        runSuite("  @BeforeClass public static void wrap() { new " + fixture + "(); }\n", "", true, 2);
    }

    /** The cleanup in @After still hands the streams back when a step before it throws. */
    @Test
    public void scaffoldingRestoresStreamsWhenTheCleanupThrows() throws Exception {
        // The test consumes the thread bookkeeping itself, so the @After call fails.
        String stopper = "try { java.lang.reflect.Field f = Suite_ESTest_scaffolding.class"
                + ".getDeclaredField(\"threadStopper\"); f.setAccessible(true);"
                + " ((org.evosuite.runtime.thread.ThreadStopper) f.get(this)).killAndJoinClientThreads();"
                + " } catch (ReflectiveOperationException e) { throw new RuntimeException(e); }";
        runSuite("", stopper, false, 1);
    }

    /** Refuses to replace System.out/err once armed, which fails the stream restore. */
    @SuppressWarnings("removal")
    public static class DenyStreamReset extends SecurityManager {
        @Override
        public void checkPermission(java.security.Permission permission) {
            if ("setIO".equals(permission.getName())) {
                throw new SecurityException("setIO denied by test");
            }
        }
    }

    public static volatile String headlessDuringTest;

    /**
     * The headless mode is handed back even when a cleanup step before it throws: here the
     * restore of the streams, which is the first step of the cleanup.
     */
    @Test
    @SuppressWarnings("removal")
    public void scaffoldingRestoresHeadlessModeWhenAnEarlierCleanupStepThrows() throws Exception {
        Assumptions.assumeFalse(java.awt.GraphicsEnvironment.isHeadless(),
                "restoring headless mode is only observable when the JVM is not headless");
        boolean headless = Properties.HEADLESS_MODE;
        Properties.HEADLESS_MODE = true;
        String property = System.getProperty("java.awt.headless");
        headlessDuringTest = null;
        try {
            String methods = new Scaffolding().getBeforeAndAfterMethods("Suite_ESTest", false,
                    Collections.emptyList());
            Assertions.assertTrue(methods.contains("restoreHeadlessMode"), methods);
            String scaffolding = "import org.junit.*; import java.io.PrintStream;\n"
                    + "import javax.swing.DebugGraphics;\n"
                    + "public class Suite_ESTest_scaffolding {\n" + methods + "}\n";
            String me = ScaffoldingStandardStreamsTest.class.getName();
            String test = "import org.junit.*;\n"
                    + "public class Suite_ESTest extends Suite_ESTest_scaffolding {\n"
                    + "  @Test public void testa() {\n"
                    + "    " + me + ".headlessDuringTest = System.getProperty(\"java.awt.headless\")"
                    + " + \"/\" + java.awt.GraphicsEnvironment.isHeadless();\n"
                    + "    System.setSecurityManager(new " + me + ".DenyStreamReset());\n"
                    + "  }\n}\n";
            Result result;
            try {
                result = compileAndRun(scaffolding, test);
            } finally {
                System.setSecurityManager(null);
            }
            // The test and the class fail, because the manager stays armed for the @AfterClass.
            Assertions.assertFalse(result.getFailures().isEmpty());
            for (org.junit.runner.notification.Failure f : result.getFailures()) {
                Assertions.assertTrue(f.getException() instanceof SecurityException,
                        result.getFailures().toString());
            }
            Assertions.assertEquals("true/true", headlessDuringTest,
                    "the generated @Before did not make the JVM headless");
            Assertions.assertEquals(property, System.getProperty("java.awt.headless"),
                    "headless mode was not restored after the stream restore threw");
            Assertions.assertFalse(java.awt.GraphicsEnvironment.isHeadless(),
                    "headless mode was not restored after the stream restore threw");
        } finally {
            Properties.HEADLESS_MODE = headless;
        }
    }

    public static volatile boolean hookRan;
    public static volatile Thread registeredHook;

    /**
     * The shutdown hooks registered by the test are still run when the thread stopper throws
     * in the cleanup: the step before them must not end the cleanup. Observable on any JVM, with
     * or without a display, and without a security manager.
     */
    @Test
    public void scaffoldingRunsShutdownHooksWhenTheThreadStopperThrows() throws Exception {
        Properties.REPLACE_CALLS = true;
        hookRan = false;
        registeredHook = null;
        String methods = new Scaffolding().getBeforeAndAfterMethods("Suite_ESTest", false,
                Collections.emptyList());
        String scaffolding = "import org.junit.*; import java.io.PrintStream;\n"
                + "import javax.swing.DebugGraphics;\n"
                + "public class Suite_ESTest_scaffolding {\n" + methods + "}\n";
        String me = ScaffoldingStandardStreamsTest.class.getName();
        String test = "import org.junit.*;\n"
                + "public class Suite_ESTest extends Suite_ESTest_scaffolding {\n"
                + "  @Test public void testa() throws Exception {\n"
                + "    java.lang.reflect.Field f = Suite_ESTest_scaffolding.class"
                + ".getDeclaredField(\"threadStopper\"); f.setAccessible(true);\n"
                + "    Thread hook = new Thread(new Runnable() { public void run() { "
                + me + ".hookRan = true; } });\n"
                + "    " + me + ".registeredHook = hook;\n"
                + "    Runtime.getRuntime().addShutdownHook(hook);\n"
                + "    ((org.evosuite.runtime.thread.ThreadStopper) f.get(this)).killAndJoinClientThreads();\n"
                + "  }\n}\n";
        try {
            Result result = compileAndRun(scaffolding, test);
            // The @After fails, because the test consumed the thread bookkeeping itself.
            Assertions.assertFalse(result.wasSuccessful(), "the cleanup was expected to throw");
            Assertions.assertTrue(hookRan,
                    "the shutdown hook was skipped after the thread stopper threw: "
                            + result.getFailures());
        } finally {
            if (registeredHook != null) {
                try {
                    Runtime.getRuntime().removeShutdownHook(registeredHook);
                } catch (IllegalStateException | IllegalArgumentException ignored) {
                    // already removed by the scaffolding, or the JVM is shutting down
                }
            }
        }
    }

    private Result compileAndRun(String scaffolding, String test) throws Exception {
        File dir = Files.createTempDirectory("scaffolding-cleanup").toFile();
        try {
            StringWriter diagnostics = new StringWriter();
            List<JavaFileObject> sources = Arrays.asList(source("Suite_ESTest_scaffolding", scaffolding),
                    source("Suite_ESTest", test));
            Boolean ok = ToolProvider.getSystemJavaCompiler().getTask(diagnostics, null, null,
                    Arrays.asList("-d", dir.getAbsolutePath(), "-cp", System.getProperty("java.class.path"),
                            "-proc:none"), null, sources).call();
            Assertions.assertTrue(ok, diagnostics + "\n" + scaffolding);
            try (URLClassLoader loader = new URLClassLoader(new URL[]{dir.toURI().toURL()},
                    getClass().getClassLoader())) {
                return new JUnitCore().run(loader.loadClass("Suite_ESTest"));
            }
        } finally {
            deleteRecursively(dir);
        }
    }

    private void runSuite(String members, String testPrologue, boolean expectSuccess,
                          int expectedDepth) throws Exception {
        String methods = new Scaffolding().getBeforeAndAfterMethods("Suite_ESTest", false,
                Collections.emptyList());
        String scaffolding = "import org.junit.*; import java.io.PrintStream;\n"
                + "import javax.swing.DebugGraphics;\n"
                + "public class Suite_ESTest_scaffolding {\n" + methods + "}\n";
        String fixture = StreamWrappingFixture.class.getName();
        StringBuilder test = new StringBuilder("import org.junit.*;\n"
                + "public class Suite_ESTest extends Suite_ESTest_scaffolding {\n" + members);
        for (String id : new String[]{"a", "b", "c", "d"}) {
            test.append("  @Test public void test").append(id).append("() { ").append(testPrologue)
                    .append(" new ").append(fixture).append("(); System.out.println(\"").append(id)
                    .append("\"); }\n");
        }
        test.append("}\n");

        List<Integer> depths;
        File dir = Files.createTempDirectory("scaffolding-streams").toFile();
        try {
            JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            StringWriter diagnostics = new StringWriter();
            List<JavaFileObject> sources = Arrays.asList(source("Suite_ESTest_scaffolding", scaffolding),
                    source("Suite_ESTest", test.toString()));
            Boolean ok = compiler.getTask(diagnostics, null, null,
                    Arrays.asList("-d", dir.getAbsolutePath(), "-cp", System.getProperty("java.class.path"),
                            "-proc:none"), null, sources).call();
            Assertions.assertTrue(ok, diagnostics + "\n" + scaffolding);

            try (StreamWrappingFixture.Recording recording = StreamWrappingFixture.record();
                 URLClassLoader loader = new URLClassLoader(new URL[]{dir.toURI().toURL()},
                         getClass().getClassLoader())) {
                Class<?> suite = loader.loadClass("Suite_ESTest");
                Result result = new JUnitCore().run(suite);
                Assertions.assertEquals(expectSuccess, result.wasSuccessful(),
                        result.getFailures().toString());
                depths = new ArrayList<>(recording.depths());
            }
        } finally {
            deleteRecursively(dir);
        }

        // The wrapper made by a @BeforeClass is a construction of its own, made first.
        int expectedConstructions = members.isEmpty() ? 4 : 5;
        Assertions.assertEquals(expectedConstructions, depths.size(), depths.toString());
        for (int depth : depths.subList(expectedConstructions - 4, expectedConstructions)) {
            Assertions.assertEquals(expectedDepth, depth,
                    "wrappers of earlier tests survived: " + depths);
        }
        Assertions.assertSame(out, System.out);
        Assertions.assertSame(err, System.err);
    }

    private static JavaFileObject source(String name, String code) {
        return new SimpleJavaFileObject(URI.create("string:///" + name + ".java"),
                JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return code;
            }
        };
    }

    private static void deleteRecursively(File f) {
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) {
                deleteRecursively(c);
            }
        }
        f.delete();
    }
}
