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
package org.evosuite.eclipse.launch;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import org.eclipse.core.runtime.*;
import org.eclipse.jdt.junit.JUnitCore;
import org.evosuite.eclipse.TestProjects;
import org.evosuite.eclipse.classpath.EvoSuiteClasspathContainer;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.*;

/** Real-engine checks of runtime-free output and read-only coverage measurement. */
public class ParityGenerationIntegrationTest {
    private GenerationOptions options() {
        assumeFalse(Boolean.getBoolean("evosuite.it.skip"));
        var options = new GenerationOptions();
        String java = System.getProperty("evosuite.it.javaHome", "");
        options.javaHome = java.equals("null") ? "" : java;
        options.searchBudget = 3;
        options.randomSeed = "12345";
        options.criterion = "LINE:BRANCH";
        options.openGeneratedTests = false;
        return options;
    }

    @Test
    public void runtimeFreeSuitesCompileWithoutRuntimeAndSupportAdd() throws Exception {
        var options = options();
        options.noRuntimeDependency = true;
        var project = TestProjects.create("runtime-free-integration", "src/main/java", null);
        TestProjects.addSource(
                project,
                "src/main/java",
                "demo.Counter",
                "package demo; public class Counter { public int sign(int n) { return n < 0 ? -1 :"
                    + " n == 0 ? 0 : 1; } }");
        TestProjects.build(project);
        var target = GenerationTarget.wholeClass("demo.Counter");
        var first = new TestGenerationJob(project, List.of(target), options, SuiteAction.ADD);
        IStatus result = first.run(new NullProgressMonitor());
        assertEquals(IStatus.OK, result.getSeverity(), result.toString());
        var suite = project.getProject().getFile("src/test/evosuite/demo/Counter_ESTest.java");
        String source = Files.readString(suite.getLocation().toFile().toPath());
        assertFalse(source.contains("org.evosuite.runtime"), source);
        assertTrue(EvoSuiteCommand.isEvoSuiteSuite(suite.getLocation().toFile()));
        assertFalse(TestProjects.hasEntry(project, EvoSuiteClasspathContainer.PATH));
        TestProjects.build(project);
        assertEquals(List.of(), TestProjects.compileErrors(project));
        var second = new TestGenerationJob(project, List.of(target), options, SuiteAction.ADD);
        result = second.run(new NullProgressMonitor());
        assertEquals(IStatus.OK, result.getSeverity(), result.toString());
        assertEquals(List.of(), second.getComparisons());
        assertTrue(EvoSuiteCommand.isEvoSuiteSuite(suite.getLocation().toFile()));
    }

    @Test
    public void coverageDoesNotGenerateTestsOrChangeProjectSetup() throws Exception {
        var options = options();
        var project = TestProjects.create("coverage-integration", "src/main/java", "src/test/java");
        TestProjects.addTestContainer(project, JUnitCore.JUNIT4_CONTAINER_PATH);
        TestProjects.addSource(
                project,
                "src/main/java",
                "demo.Counter",
                "package demo; public class Counter { public int abs(int n) { return n < 0 ? -n :"
                    + " n; } }");
        TestProjects.addSource(
                project,
                "src/test/java",
                "demo.CounterTest",
                "package demo; import org.junit.Test; import static org.junit.Assert.*; public"
                    + " class CounterTest { @Test public void negative() { assertEquals(3, new"
                    + " Counter().abs(-3)); } }");
        TestProjects.build(project);
        assertEquals(List.of(), TestProjects.compileErrors(project));
        var before = List.of(project.getRawClasspath());
        var target =
                GenerationTarget.extending(
                        "demo.Counter",
                        List.of(
                                new GenerationTarget.Seed(
                                        "demo.CounterTest",
                                        project.getProject()
                                                .getFolder("src/test/java")
                                                .getLocation()
                                                .toFile())));
        var job =
                new TestGenerationJob(project, List.of(target), options, SuiteAction.COMPARE, true);
        IStatus result = job.run(new NullProgressMonitor());
        assertEquals(IStatus.OK, result.getSeverity(), result.toString());
        assertEquals(before, List.of(project.getRawClasspath()));
        assertFalse(project.getProject().getFolder(options.testFolder).exists());
        assertEquals(List.of(), job.getGeneratedSuites());
        var record =
                RunHistory.entries().stream()
                        .filter(e -> e.get("project").equals(project.getElementName()))
                        .findFirst()
                        .orElseThrow();
        assertEquals("Coverage measured", record.get("status"));
        assertTrue(
                Files.isRegularFile(
                        java.nio.file.Path.of(record.get("reports")).resolve("statistics.csv")));
    }
}
