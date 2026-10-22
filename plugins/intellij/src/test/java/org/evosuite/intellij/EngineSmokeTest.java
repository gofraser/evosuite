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
package org.evosuite.intellij;

import junit.framework.TestCase;

import org.evosuite.ide.*;

import java.nio.file.*;
import java.util.*;

/** A real engine/JDK boundary test, independent of mocked platform processes. */
public class EngineSmokeTest extends TestCase {
    public void testMeasureExistingCoverageWritesReportWithoutGeneratingTests() throws Exception {
        Path home =
                Path.of(
                        System.getProperty(
                                "evosuite.it.javaHome", System.getProperty("java.home")));
        Path root = Files.createTempDirectory("evosuite-coverage-");
        try {
            Files.createDirectories(root.resolve("demo"));
            Path cut = root.resolve("demo/Calculator.java"),
                    test = root.resolve("demo/CalculatorTest.java");
            Files.writeString(
                    cut,
                    "package demo; public class Calculator { public int sign(int n){return n < 0 ?"
                            + " -1 : 1;} }");
            Files.writeString(
                    test,
                    "package demo; public class CalculatorTest { @org.junit.Test public void"
                        + " positive(){org.junit.Assert.assertEquals(1,new Calculator().sign(1));}"
                        + " }");
            String junit = com.intellij.util.PathUtil.getJarPathForClass(org.junit.Test.class);
            String hamcrest =
                    com.intellij.util.PathUtil.getJarPathForClass(org.hamcrest.Matcher.class);
            String cp =
                    root
                            + java.io.File.pathSeparator
                            + junit
                            + java.io.File.pathSeparator
                            + hamcrest;
            assertEquals(
                    0,
                    new ProcessBuilder(
                                    home.resolve("bin/javac").toString(),
                                    "-cp",
                                    cp,
                                    "-d",
                                    root.toString(),
                                    cut.toString(),
                                    test.toString())
                            .inheritIO()
                            .start()
                            .waitFor());
            var options = new GenerationOptions();
            options.criterion = "LINE:BRANCH";
            options.testFormat = "JUNIT4";
            options.clientMemory = 512;
            List<String> log = new java.util.concurrent.CopyOnWriteArrayList<>();
            var target =
                    new GenerationTarget(
                            "demo.Calculator",
                            List.of(),
                            List.of(
                                    new GenerationTarget.Seed(
                                            "demo.CalculatorTest", root.toFile())));
            int exit =
                    GenerationRun.measureCoverage(
                            target,
                            options,
                            JavaInstallation.at(home),
                            Path.of(System.getProperty("evosuite.engine")),
                            List.of(root.toString(), junit, hamcrest),
                            root,
                            root.resolve("run"),
                            listener(log),
                            Map.of(),
                            () -> false);
            assertEquals(String.join("\n", log), 0, exit);
            assertTrue(
                    String.join("\n", log),
                    Files.isRegularFile(root.resolve("run/reports/statistics.csv")));
            assertFalse(Files.exists(root.resolve("run/tests/demo/Calculator_ESTest.java")));
        } finally {
            GenerationRun.deleteTree(root);
        }
    }

    public void testRuntimeFreeOutputCompilesAndRunsWithoutEvoSuiteRuntime() throws Exception {
        assertRuntimeFreeOutput(
                "package demo; public class Calculator { public int sign(int n) { return n < 0 ? -1"
                        + " : 1; } }");
    }

    public void testRuntimeFreeOutputDoesNotRequireMockClockOrFilesystem() throws Exception {
        assertRuntimeFreeOutput(
                "package demo; public class Calculator { public long clock() { return"
                        + " System.currentTimeMillis(); } public int exists(String p) { return new"
                        + " java.io.File(p).exists() ? 1 : 0; } }");
    }

    private void assertRuntimeFreeOutput(String productionSource) throws Exception {
        Path home =
                Path.of(
                        System.getProperty(
                                "evosuite.it.javaHome", System.getProperty("java.home")));
        if (!Files.isDirectory(home.resolve("bin")))
            home = Path.of(System.getProperty("java.home"));
        Path root = Files.createTempDirectory("evosuite-runtime-free-");
        try {
            Path source = root.resolve("demo/Calculator.java");
            Files.createDirectories(source.getParent());
            Files.writeString(source, productionSource);
            assertEquals(
                    0,
                    new ProcessBuilder(
                                    home.resolve("bin/javac").toString(),
                                    "-d",
                                    root.toString(),
                                    source.toString())
                            .inheritIO()
                            .start()
                            .waitFor());
            var options = new GenerationOptions();
            options.noRuntimeDependency = true;
            options.randomSeed = "12345";
            options.testFormat = "JUNIT4";
            options.searchBudget = 2;
            options.clientMemory = 512;
            options.criterion = "BRANCH";
            options.extraArguments = "-Dminimization_timeout=2 -Dassertion_timeout=2";
            List<String> log = new java.util.concurrent.CopyOnWriteArrayList<>();
            var result =
                    GenerationRun.generate(
                            GenerationTarget.wholeClass("demo.Calculator"),
                            options,
                            JavaInstallation.at(home),
                            Path.of(System.getProperty("evosuite.engine")),
                            List.of(root.toString()),
                            root,
                            root.resolve("run"),
                            listener(log),
                            Map.of(),
                            () -> false);
            assertTrue(String.join("\n", log), result.produced());
            String generatedSource = Files.readString(result.suite());
            assertFalse(generatedSource, generatedSource.contains("org.evosuite."));
            if (productionSource.contains("long clock")) {
                assertTrue(generatedSource, generatedSource.contains(".clock()"));
                assertTrue(generatedSource, generatedSource.contains(".exists("));
            }
            String junit = com.intellij.util.PathUtil.getJarPathForClass(org.junit.Test.class);
            String hamcrest =
                    com.intellij.util.PathUtil.getJarPathForClass(org.hamcrest.Matcher.class);
            String cp =
                    root
                            + java.io.File.pathSeparator
                            + junit
                            + java.io.File.pathSeparator
                            + hamcrest;
            assertEquals(
                    "Compile without EvoSuite jars",
                    0,
                    new ProcessBuilder(
                                    home.resolve("bin/javac").toString(),
                                    "-cp",
                                    cp,
                                    "-d",
                                    root.toString(),
                                    result.suite().toString())
                            .inheritIO()
                            .start()
                            .waitFor());
            assertEquals(
                    "Run without EvoSuite jars",
                    0,
                    new ProcessBuilder(
                                    home.resolve("bin/java").toString(),
                                    "-cp",
                                    cp,
                                    "org.junit.runner.JUnitCore",
                                    "demo.Calculator_ESTest")
                            .inheritIO()
                            .start()
                            .waitFor());
        } finally {
            GenerationRun.deleteTree(root);
        }
    }

    public void testJdk21GenerationKeepsSandboxAvailableInMasterAndClient() throws Exception {
        Path home = Path.of(System.getProperty("java.home"));
        var java = JavaInstallation.at(home);
        assertEquals("The IntelliJ test runtime is JDK 21", 21, java.version());
        Path root = Files.createTempDirectory("evosuite-jdk21-sandbox-");
        List<String> log = new java.util.concurrent.CopyOnWriteArrayList<>();
        try {
            Path source = root.resolve("demo/Calculator.java");
            Files.createDirectories(source.getParent());
            Files.writeString(
                    source,
                    "package demo; public class Calculator { public int sign(int n) { return n < 0"
                            + " ? -1 : 1; } }");
            Process compile =
                    new ProcessBuilder(
                                    home.resolve("bin/javac").toString(),
                                    "-d",
                                    root.toString(),
                                    source.toString())
                            .inheritIO()
                            .start();
            assertEquals(0, compile.waitFor());
            var options = new GenerationOptions();
            options.searchBudget = 2;
            options.clientMemory = 512;
            options.criterion = "BRANCH";
            options.testFormat = "JUNIT4";
            options.extraArguments = "-Dminimization_timeout=2 -Dassertion_timeout=2";
            var result =
                    GenerationRun.generate(
                            GenerationTarget.wholeClass("demo.Calculator"),
                            options,
                            java,
                            Path.of(System.getProperty("evosuite.engine")),
                            List.of(root.toString()),
                            root,
                            root.resolve("run"),
                            listener(log),
                            Map.of("JAVA_HOME", "/wrong-inherited-jdk"),
                            () -> false);
            String output = String.join("\n", log);
            assertTrue(output, result.produced());
            assertEquals(output, 0, result.exitCode());
            assertTrue(
                    output,
                    SuiteMerger.testCount(Files.readString(result.suite()), "Calculator_ESTest")
                            > 0);
            assertFalse(output, output.contains("The sandbox is off"));
            assertFalse(output, output.contains("UnsupportedOperationException"));
        } finally {
            GenerationRun.deleteTree(root);
        }
    }

    public void testBundledEngineGeneratesTests() throws Exception {
        if (Boolean.getBoolean("evosuite.it.skip")) return;
        Path engine = Path.of(System.getProperty("evosuite.engine"));
        assertTrue("Build the engine first: " + engine, Files.isRegularFile(engine));
        String configured = System.getProperty("evosuite.it.javaHome", "");
        Path home = Path.of(configured.isBlank() ? System.getProperty("java.home") : configured);
        Path root = Files.createTempDirectory("evosuite-intellij-engine-");
        try {
            Path source = root.resolve("demo/Calculator.java");
            Files.createDirectories(source.getParent());
            Files.writeString(
                    source,
                    "package demo; public class Calculator { public int sign(int n) { if(n<0)"
                            + " return -1; if(n>0) return 1; return 0; } }");
            Process compile =
                    new ProcessBuilder(
                                    home.resolve("bin/javac").toString(),
                                    "-d",
                                    root.toString(),
                                    source.toString())
                            .inheritIO()
                            .start();
            assertEquals(0, compile.waitFor());
            GenerationOptions options = new GenerationOptions();
            options.searchBudget = 2;
            options.criterion = "BRANCH";
            options.clientMemory = 512;
            options.testFormat = "JUNIT4";
            options.extraArguments =
                    "-Dsandbox=false -Dminimization_timeout=2 -Dassertion_timeout=2";
            List<String> log = new java.util.concurrent.CopyOnWriteArrayList<>();
            GenerationRun.Result result =
                    GenerationRun.generate(
                            GenerationTarget.wholeClass("demo.Calculator"),
                            options,
                            JavaInstallation.at(home),
                            engine,
                            List.of(root.toString()),
                            root,
                            root.resolve("run"),
                            new ProgressParser.Listener() {
                                public void line(String line) {
                                    log.add(line);
                                }

                                public void progress(int search, int coverage) {}
                            },
                            Map.of(),
                            () -> false);
            assertTrue(String.join("\n", log), result.produced());
            assertTrue(
                    String.join("\n", log),
                    SuiteMerger.testCount(Files.readString(result.suite()), "Calculator_ESTest")
                            > 0);
            String junit = com.intellij.util.PathUtil.getJarPathForClass(org.junit.Test.class);
            String cp =
                    root
                            + java.io.File.pathSeparator
                            + System.getProperty("evosuite.runtime")
                            + java.io.File.pathSeparator
                            + junit;
            Process tests =
                    new ProcessBuilder(
                                    home.resolve("bin/javac").toString(),
                                    "-cp",
                                    cp,
                                    "-d",
                                    root.toString(),
                                    result.suite().toString())
                            .inheritIO()
                            .start();
            assertEquals("Generated suite must compile", 0, tests.waitFor());
            GenerationTarget method = new GenerationTarget("demo.Calculator", List.of("sign(I)I"));
            var methodResult =
                    GenerationRun.generate(
                            method,
                            options,
                            JavaInstallation.at(home),
                            engine,
                            List.of(root.toString()),
                            root,
                            root.resolve("method-run"),
                            listener(log),
                            Map.of(),
                            () -> false);
            assertTrue(String.join("\n", log), methodResult.produced());
            assertTrue(
                    SuiteMerger.testCount(
                                    Files.readString(methodResult.suite()), "Calculator_ESTest")
                            > 0);

            Path seed = root.resolve("demo/CalculatorTest.java");
            Files.writeString(
                    seed,
                    "package demo; import org.junit.Test; import static org.junit.Assert.*; public"
                            + " class CalculatorTest { @Test public void positive() { Calculator"
                            + " calculator = new Calculator(); int result = calculator.sign(2);"
                            + " assertEquals(1, result); } }");
            Process seedCompile =
                    new ProcessBuilder(
                                    home.resolve("bin/javac").toString(),
                                    "-cp",
                                    cp,
                                    "-d",
                                    root.toString(),
                                    seed.toString())
                            .inheritIO()
                            .start();
            assertEquals(0, seedCompile.waitFor());
            options.searchBudget = 10;
            options.extraArguments =
                    "-Dsandbox=false -Dminimization_timeout=10 -Dassertion_timeout=5";
            var extension =
                    GenerationRun.generate(
                            GenerationTarget.extending(
                                    "demo.Calculator",
                                    List.of(
                                            new GenerationTarget.Seed(
                                                    "demo.CalculatorTest", root.toFile()))),
                            options,
                            JavaInstallation.at(home),
                            engine,
                            List.of(root.toString(), junit),
                            root,
                            root.resolve("extension-run"),
                            listener(log),
                            Map.of(),
                            () -> false);
            assertNotNull(extension.seeding());
            assertTrue(String.join("\n", log), extension.seeding().seeded());
            assertTrue(String.join("\n", log), extension.seeding().ranExistingTests());
        } finally {
            GenerationRun.deleteTree(root);
        }
    }

    private static ProgressParser.Listener listener(List<String> log) {
        return new ProgressParser.Listener() {
            public void line(String line) {
                log.add(line);
            }

            public void progress(int search, int coverage) {}
        };
    }
}
