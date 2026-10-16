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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IType;
import org.evosuite.eclipse.TestProjects;
import org.evosuite.eclipse.launch.GenerationTarget;
import org.evosuite.eclipse.launch.SuiteAction;
import org.evosuite.eclipse.launch.TestGenerationJob;
import org.evosuite.eclipse.preferences.EvoSuitePreferences;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Turning selected test classes into runs that extend them. */
public class ExtendTestsHandlerTest {

    private final IEclipsePreferences preferences = InstanceScope.INSTANCE.getNode(EvoSuitePreferences.QUALIFIER);
    private IJavaProject project;
    private final List<String> asked = new ArrayList<>();

    @BeforeEach
    public void createProject() throws Exception {
        project = TestProjects.create("extend-handler", "src/main/java", "src/test/java");
        TestProjects.addSource(project, "src/main/java", "a.Vault", "package a; public class Vault {}");
        TestProjects.addSource(project, "src/main/java", "a.Lock", "package a; public class Lock {}");
        for (String name : List.of("VaultTest", "VaultIT", "LockTest", "VaultJ5Test")) {
            TestProjects.addSource(project, "src/test/java", "a." + name, "package a; public class " + name + " {}");
        }
        ExtendTestsHandler.chooser = (shell, test, inference) -> {
            asked.add(test.getElementName() + " -> " + inference.guess());
            return inference.guess().isEmpty() ? null : project.findType("a." + inference.guess());
        };
    }

    @AfterEach
    public void restore() throws Exception {
        ExtendTestsHandler.chooser = ExtendTestsHandler::askForClassUnderTest;
        GenerateTestsHandler.chooser = ExistingSuitesDialog::ask;
        GenerateTestsHandler.informer = GenerateTestsHandler::showInformation;
        preferences.clear();
    }

    private IType type(String name) throws Exception {
        return project.findType("a." + name);
    }

    @Test
    public void theTestClassesOfOneClassShareARun() throws Exception {
        List<String> skipped = new ArrayList<>();
        Map<IJavaProject, List<GenerationTarget>> requests = ExtendTestsHandler.targets(null,
                List.of(type("VaultTest"), type("LockTest"), type("VaultIT"), type("VaultJ5Test")), skipped);

        assertEquals(List.of(), skipped);
        // Only VaultJ5Test's name leaves the class open, so only it was asked about.
        assertEquals(List.of("VaultJ5Test -> Vault"), asked);
        List<GenerationTarget> targets = requests.get(project);
        assertEquals(List.of("a.Vault", "a.Lock"), targets.stream().map(GenerationTarget::className).toList());
        File testRoot = project.getProject().getFolder("src/test/java").getLocation().toFile();
        assertEquals(List.of(new GenerationTarget.Seed("a.VaultTest", testRoot),
                new GenerationTarget.Seed("a.VaultIT", testRoot), new GenerationTarget.Seed("a.VaultJ5Test", testRoot)),
                targets.get(0).seeds());
        assertEquals("VaultTest, VaultIT and VaultJ5Test", targets.get(0).seedNames());
        assertEquals(List.of(), targets.get(0).methods());
        assertTrue(new File(testRoot, "a/VaultJ5Test.java").equals(targets.get(0).seeds().get(2).sourceFile()));
    }

    @Test
    public void aTestWithoutAChosenClassIsLeftOut() throws Exception {
        TestProjects.addSource(project, "src/test/java", "a.Checks", "package a; public class Checks {}");
        Map<IJavaProject, List<GenerationTarget>> requests = ExtendTestsHandler.targets(null,
                List.of(type("Checks")), new ArrayList<>());

        assertEquals(List.of("Checks -> "), asked);
        assertEquals(Map.of(), requests);
    }

    /**
     * With the test folder and suffix of the project's own tests, the EvoSuite suite of a class
     * would be its hand-written test, which must stay as it is.
     */
    @Test
    public void neverWritesIntoAHandWrittenTestClass() throws Exception {
        preferences.put(EvoSuitePreferences.TEST_FOLDER, "src/test/java");
        preferences.put(EvoSuitePreferences.TEST_SUFFIX, "Test");
        List<String> messages = new ArrayList<>();
        List<ExistingSuitesQuestion> questions = new ArrayList<>();
        GenerateTestsHandler.informer = (shell, message) -> messages.add(message);
        GenerateTestsHandler.chooser = (shell, question) -> {
            questions.add(question);
            return null;
        };
        GenerateTestsHandler.launch(null, ExtendTestsHandler.targets(null, List.of(type("VaultTest"), type("VaultIT")),
                new ArrayList<>()), ExistingSuitesQuestion.Scope.EXTENSION);

        assertEquals(List.of("No tests are generated:\n\nVault: the new tests would go into VaultTest.java, which is "
                + "not a suite generated by EvoSuite and stays as it is. Choose another test class suffix or folder "
                + "in the EvoSuite settings."), messages);
        assertEquals(List.of(), questions);
        assertEquals(0, Job.getJobManager().find(TestGenerationJob.FAMILY).length, "generation started anyway");
    }

    @Test
    public void anExistingSuiteIsExtendedByAddingToItUnlessTheUserSaysOtherwise() throws Exception {
        TestProjects.addSource(project, "src/test/evosuite", "a.Vault_ESTest",
                "package a; @org.evosuite.runtime.EvoRunnerParameters class Vault_ESTest {}");
        List<ExistingSuitesQuestion> questions = new ArrayList<>();
        GenerateTestsHandler.chooser = (shell, question) -> {
            questions.add(question);
            return null;
        };
        GenerateTestsHandler.launch(null, ExtendTestsHandler.targets(null, List.of(type("VaultTest")),
                new ArrayList<>()), ExistingSuitesQuestion.Scope.EXTENSION);

        assertEquals(1, questions.size());
        assertEquals(SuiteAction.ADD, questions.get(0).defaultAction);
        assertTrue(questions.get(0).message.contains("only cover what the existing tests leave uncovered"),
                questions.get(0).message);
        assertEquals(0, Job.getJobManager().find(TestGenerationJob.FAMILY).length, "generation started anyway");
    }
}
