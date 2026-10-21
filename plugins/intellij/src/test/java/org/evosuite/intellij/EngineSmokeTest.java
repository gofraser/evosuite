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
