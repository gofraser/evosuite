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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.junit.JUnitCore;
import org.evosuite.eclipse.TestProjects;
import org.evosuite.eclipse.classpath.EvoSuiteClasspathContainer;
import org.evosuite.eclipse.preferences.EvoSuitePreferences;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * End to end: the bundled EvoSuite generates tests for a class of a fresh Java project, and the
 * project compiles them. Takes about a minute; skip with {@code -Devosuite.it.skip=true}.
 */
public class TestGenerationJobTest {

    private static final String CALCULATOR = "package demo;\n"
            + "public class Calculator {\n"
            + "    public int max(int a, int b) {\n"
            + "        if (a > b) {\n"
            + "            return a;\n"
            + "        }\n"
            + "        return b;\n"
            + "    }\n"
            + "    public String sign(int x) {\n"
            + "        if (x < 0) {\n"
            + "            return \"negative\";\n"
            + "        }\n"
            + "        return x == 0 ? \"zero\" : \"positive\";\n"
            + "    }\n"
            + "}\n";

    private final IEclipsePreferences preferences = InstanceScope.INSTANCE.getNode(EvoSuitePreferences.QUALIFIER);

    @BeforeEach
    public void configure() {
        assumeFalse(Boolean.getBoolean("evosuite.it.skip"), "end-to-end test skipped");
        // Tycho passes an empty property on as the string "null".
        String javaHome = System.getProperty("evosuite.it.javaHome", "");
        preferences.put(EvoSuitePreferences.JAVA_HOME, "null".equals(javaHome) ? "" : javaHome);
    }

    @AfterEach
    public void restore() throws Exception {
        preferences.clear();
    }

    @Test
    public void generatesATestSuiteThatCompiles() throws Exception {
        preferences.putInt(EvoSuitePreferences.SEARCH_BUDGET, 10);
        IJavaProject project = TestProjects.create("generation", "src/main/java", null);
        TestProjects.addSource(project, "src/main/java", "demo.Calculator", CALCULATOR);
        TestProjects.build(project);

        TestGenerationJob job = new TestGenerationJob(project, List.of("demo.Calculator"));
        job.schedule();
        assertTrue(job.join(TimeUnit.MINUTES.toMillis(5), null), "generation did not finish in time");

        IStatus result = job.getResult();
        assertEquals(IStatus.OK, result.getSeverity(), result::toString);
        IFile suite = project.getProject().getFile("src/test/evosuite/demo/Calculator_ESTest.java");
        assertTrue(suite.exists(), "no test suite in the workspace");
        assertEquals(List.of(suite), job.getGeneratedSuites());

        // The suite compiles against the libraries the job added to the build path.
        TestProjects.build(project);
        assertEquals(List.of(), TestProjects.compileErrors(project));
        assertTrue(TestProjects.hasEntry(project, JUnitCore.JUNIT5_CONTAINER_PATH), "JUnit 5 not added");
        assertTrue(TestProjects.hasEntry(project, EvoSuiteClasspathContainer.PATH), "EvoSuite runtime not added");
        // EvoSuite wrote its statistics to the plug-in's state area, not into the project.
        assertTrue(!project.getProject().getFolder("evosuite-report").exists(), "report folder in the project");
    }

    @Test
    public void cancellingStopsEvoSuiteAndKeepsFinishedSuites() throws Exception {
        preferences.putInt(EvoSuitePreferences.SEARCH_BUDGET, 10);
        IJavaProject project = TestProjects.create("cancellation", "src/main/java", null);
        TestProjects.addSource(project, "src/main/java", "demo.Calculator", CALCULATOR);
        TestProjects.addSource(project, "src/main/java", "demo.Other",
                CALCULATOR.replace("class Calculator", "class Other"));
        TestProjects.build(project);

        TestGenerationJob job = new TestGenerationJob(project, List.of("demo.Calculator", "demo.Other"));
        job.schedule();
        long deadline = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(3);
        while (job.getGeneratedSuites().isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(200);
        }
        assertEquals(1, job.getGeneratedSuites().size(), "the first class was not done in time");
        // Long enough for the second class's master and client to be running, short of its budget.
        Thread.sleep(TimeUnit.SECONDS.toMillis(4));
        assertTrue(ProcessHandle.current().descendants().anyMatch(ProcessHandle::isAlive), "EvoSuite is not running");
        job.cancel();
        assertTrue(job.join(TimeUnit.SECONDS.toMillis(30), null), "cancelled generation did not stop");

        assertEquals(IStatus.CANCEL, job.getResult().getSeverity());
        assertEquals(0, ProcessHandle.current().descendants().filter(ProcessHandle::isAlive).count(),
                "EvoSuite processes outlived the cancelled job");
        IFile first = project.getProject().getFile("src/test/evosuite/demo/Calculator_ESTest.java");
        assertTrue(first.exists() && first.isSynchronized(IResource.DEPTH_ZERO),
                "the suite finished before the cancel is not in the workspace");
    }
}
