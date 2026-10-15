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
package org.evosuite.eclipse.handlers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jdt.core.IJavaProject;
import org.evosuite.eclipse.TestProjects;
import org.evosuite.eclipse.launch.GenerationOptions;
import org.evosuite.eclipse.launch.GenerationTarget;
import org.evosuite.eclipse.launch.TestGenerationJob;
import org.junit.jupiter.api.Test;

public class GenerateTestsHandlerTest {

    @Test
    public void recognisesEvoSuiteOutputButNotClassesThatShareItsSuffix() throws Exception {
        IJavaProject project = TestProjects.create("generated", "src", null);
        TestProjects.addSource(project, "src", "g.Foo_ESTest",
                "package g; @org.evosuite.runtime.EvoRunnerParameters public class Foo_ESTest {}");
        TestProjects.addSource(project, "src", "g.Bar_ESTest",
                "package g; import org.evosuite.runtime.EvoRunnerParameters; @EvoRunnerParameters public class Bar_ESTest {}");
        TestProjects.addSource(project, "src", "g.Foo_ESTest_scaffolding", "package g; public class Foo_ESTest_scaffolding {}");
        TestProjects.addSource(project, "src", "g.Lone_ESTest_scaffolding", "package g; public class Lone_ESTest_scaffolding {}");
        TestProjects.addSource(project, "src", "g.ServiceTest", "package g; public class ServiceTest {}");

        GenerationOptions options = new GenerationOptions();
        assertTrue(GenerateTestsHandler.isGeneratedTest(project.findType("g.Foo_ESTest"), options));
        assertTrue(GenerateTestsHandler.isGeneratedTest(project.findType("g.Bar_ESTest"), options));
        assertTrue(GenerateTestsHandler.isGeneratedTest(project.findType("g.Foo_ESTest_scaffolding"), options));
        assertFalse(GenerateTestsHandler.isGeneratedTest(project.findType("g.Lone_ESTest_scaffolding"), options));

        options.testSuffix = "Test";
        assertFalse(GenerateTestsHandler.isGeneratedTest(project.findType("g.ServiceTest"), options));
    }

    @Test
    public void asksOnceForAllProjectsAndStartsNothingWhenCancelled() throws Exception {
        Map<IJavaProject, List<GenerationTarget>> requests = new LinkedHashMap<>();
        for (String name : List.of("ask-a", "ask-b")) {
            IJavaProject project = TestProjects.create(name, "src", null);
            TestProjects.addSource(project, "src/test/evosuite", "demo.Calculator_ESTest", "class Calculator_ESTest {}");
            requests.put(project, List.of(new GenerationTarget("demo.Calculator", List.of("max(II)I")),
                    GenerationTarget.wholeClass("demo.Other")));
        }
        List<ExistingSuitesQuestion> asked = new ArrayList<>();
        GenerateTestsHandler.chooser = (shell, question) -> {
            asked.add(question);
            return null;
        };
        try {
            GenerateTestsHandler.launch(null, requests, true);
        } finally {
            GenerateTestsHandler.chooser = ExistingSuitesDialog::ask;
        }

        assertEquals(1, asked.size());
        assertTrue(asked.get(0).message.startsWith("2 of the 4 classes already have test suites in "
                + "src/test/evosuite: Calculator, Calculator."), asked.get(0).message);
        assertEquals(org.evosuite.eclipse.launch.SuiteAction.ADD, asked.get(0).defaultAction);
        assertEquals(0, Job.getJobManager().find(TestGenerationJob.FAMILY).length, "generation started anyway");
    }
}
