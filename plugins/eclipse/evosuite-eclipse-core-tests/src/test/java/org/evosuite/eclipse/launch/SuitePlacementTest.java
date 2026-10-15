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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.eclipse.core.filebuffers.FileBuffers;
import org.eclipse.core.filebuffers.ITextFileBuffer;
import org.eclipse.core.filebuffers.ITextFileBufferManager;
import org.eclipse.core.filebuffers.LocationKind;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourceAttributes;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.jdt.core.IJavaProject;
import org.evosuite.eclipse.Activator;
import org.evosuite.eclipse.TestProjects;
import org.evosuite.eclipse.preferences.EvoSuitePreferences;
import org.evosuite.eclipse.ui.SuiteComparison;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

/**
 * Where a run's new suite ends up, and how a run copes with problems, with a stand-in for
 * EvoSuite that writes a suite where the command line tells it to: as EvoSuite does, it takes the
 * last value of each property.
 */
public class SuitePlacementTest {

    private static final String CALCULATOR = "package demo;\n"
            + "public class Calculator {\n"
            + "    public int max(int a, int b) {\n"
            + "        return a > b ? a : b;\n"
            + "    }\n"
            + "}\n";

    /** The suite a user had before a run, with a test of their own. */
    private static final String EXISTING = SuiteMergerTest.suite("", SuiteMergerTest.test("test0",
            "Calculator calculator0 = new Calculator();", "int int0 = calculator0.max(1, 2);",
            "assertEquals(2, int0);"));
    /** The suite the stand-in writes. */
    private static final String GENERATED = SuiteMergerTest.suite("", SuiteMergerTest.test("test0",
            "Calculator calculator0 = new Calculator();", "int int0 = calculator0.max(7, 3);",
            "assertEquals(7, int0);"));
    private static final String SCAFFOLDING = "package demo;\npublic class Calculator_ESTest_scaffolding {\n"
            + "  // classes: demo.Calculator\n}\n";

    private final IEclipsePreferences preferences = InstanceScope.INSTANCE.getNode(EvoSuitePreferences.QUALIFIER);
    /** The command lines the stand-in ran. */
    private final List<List<String>> runs = new CopyOnWriteArrayList<>();
    private IJavaProject project;
    private IFile suite;
    private IFile scaffolding;

    @BeforeEach
    public void createProject(TestInfo test) throws Exception {
        String javaHome = System.getProperty("evosuite.it.javaHome", "");
        preferences.put(EvoSuitePreferences.JAVA_HOME, "null".equals(javaHome) ? "" : javaHome);
        preferences.putBoolean(EvoSuitePreferences.OPEN_GENERATED_TESTS, false);
        // A project of its own for each test: the local history outlives a deleted project.
        project = TestProjects.create("placement-" + test.getTestMethod().orElseThrow().getName(), "src/main/java",
                null);
        TestProjects.addSource(project, "src/main/java", "demo.Calculator", CALCULATOR);
        TestProjects.build(project);
        suite = project.getProject().getFile("src/test/evosuite/demo/Calculator_ESTest.java");
        scaffolding = project.getProject().getFile("src/test/evosuite/demo/Calculator_ESTest_scaffolding.java");
        TestGenerationJob.runner = (commandLine, workingDirectory, environment, listener, cancelled) -> {
            runs.add(commandLine);
            File dir = new File(last(commandLine, "-Dtest_dir="));
            String className = commandLine.get(commandLine.indexOf("-class") + 1);
            File file = EvoSuiteCommand.suiteFile(dir, className, last(commandLine, "-Djunit_suffix="));
            file.getParentFile().mkdirs();
            Files.writeString(file.toPath(), GENERATED);
            if ("true".equals(last(commandLine, "-Dtest_scaffolding="))) {
                Files.writeString(new File(file.getParentFile(), file.getName().replace(".java", "_scaffolding.java"))
                        .toPath(), SCAFFOLDING);
            }
            return 0;
        };
    }

    @AfterEach
    public void restore() throws Exception {
        TestGenerationJob.runner = EvoSuiteProcess::run;
        preferences.clear();
    }

    private static String last(List<String> commandLine, String prefix) {
        String value = null;
        for (String argument : commandLine) {
            if (argument.startsWith(prefix)) {
                value = argument.substring(prefix.length());
            }
        }
        return value;
    }

    private TestGenerationJob job(GenerationTarget target, SuiteAction action) {
        return new TestGenerationJob(project, List.of(target), EvoSuitePreferences.resolve(project.getProject(), null),
                action);
    }

    private static IStatus run(TestGenerationJob job) {
        return job.run(new NullProgressMonitor());
    }

    private static final GenerationTarget WHOLE_CLASS = GenerationTarget.wholeClass("demo.Calculator");
    private static final GenerationTarget ONE_METHOD = new GenerationTarget("demo.Calculator", List.of("max(II)I"));

    @Test
    public void replacingKeepsThePreviousSuiteInTheLocalHistory() throws Exception {
        write(suite, EXISTING);
        TestGenerationJob job = job(WHOLE_CLASS, SuiteAction.REPLACE);

        IStatus result = run(job);

        assertEquals(IStatus.OK, result.getSeverity(), result::toString);
        assertEquals(GENERATED, read(suite));
        assertEquals(List.of(suite), job.getGeneratedSuites());
        assertEquals(List.of(EXISTING), history(suite));
        // EvoSuite itself wrote into the temporary folder, never into the test folder.
        String testDir = last(runs.get(0), "-Dtest_dir=");
        assertFalse(new File(testDir).equals(suite.getParent().getParent().getLocation().toFile()), testDir);
    }

    /**
     * Runs queue behind each other, and a run that found no suite when it was started must not
     * replace one that an earlier run wrote meanwhile, and the user perhaps edited.
     */
    @Test
    public void aSuiteWrittenWhileTheRunWaitedIsComparedNotReplaced() throws Exception {
        TestGenerationJob job = job(WHOLE_CLASS, SuiteAction.REPLACE);
        write(suite, EXISTING);

        IStatus result = run(job);

        assertEquals(IStatus.OK, result.getSeverity(), result::toString);
        assertEquals(EXISTING, read(suite));
        assertEquals(List.of(new SuiteComparison.Pair(suite, GENERATED)), job.getComparisons());
        assertTrue(job.outcomes().get(0).startsWith("Calculator_ESTest.java was written while this run waited or "
                + "ran, so it is not replaced"), job.outcomes().toString());
    }

    /** For part of a class, the new tests are added to a suite that appeared meanwhile. */
    @Test
    public void testsForAMethodAreAddedToASuiteWrittenWhileTheRunWaited() throws Exception {
        TestGenerationJob job = job(ONE_METHOD, SuiteAction.REPLACE);
        write(suite, EXISTING);

        IStatus result = run(job);

        assertEquals(IStatus.OK, result.getSeverity(), result::toString);
        String merged = read(suite);
        assertTrue(merged.contains("calculator0.max(1, 2)") && merged.contains("calculator0.max(7, 3)"), merged);
        assertEquals(List.of(), job.getComparisons());
        assertEquals(List.of(EXISTING), history(suite));
    }

    @Test
    public void aSuiteWithUnsavedChangesIsNotReplaced() throws Exception {
        write(suite, EXISTING);
        ITextFileBufferManager manager = FileBuffers.getTextFileBufferManager();
        manager.connect(suite.getFullPath(), LocationKind.IFILE, null);
        try {
            ITextFileBuffer buffer = manager.getTextFileBuffer(suite.getFullPath(), LocationKind.IFILE);
            buffer.getDocument().replace(0, 0, "// being edited\n");
            TestGenerationJob job = job(WHOLE_CLASS, SuiteAction.REPLACE);

            IStatus result = run(job);

            assertEquals(IStatus.OK, result.getSeverity(), result::toString);
            assertEquals(EXISTING, read(suite));
            assertEquals(List.of(new SuiteComparison.Pair(suite, GENERATED)), job.getComparisons());
            assertTrue(job.outcomes().contains("Cannot replace Calculator_ESTest.java: Calculator_ESTest.java has "
                    + "unsaved changes in an editor. The new suite opens next to it in a compare editor instead."),
                    job.outcomes().toString());
        } finally {
            manager.disconnect(suite.getFullPath(), LocationKind.IFILE, null);
        }
    }

    /** The new tests are not lost when the suite they belong in cannot be written. */
    @Test
    public void aSuiteThatCannotBeWrittenIsComparedInstead() throws Exception {
        for (SuiteAction action : List.of(SuiteAction.ADD, SuiteAction.REPLACE)) {
            write(suite, EXISTING);
            setReadOnly(suite, true);
            try {
                TestGenerationJob job = job(WHOLE_CLASS, action);

                IStatus result = run(job);

                assertEquals(IStatus.OK, result.getSeverity(), action + ": " + result);
                assertEquals(EXISTING, read(suite), action.toString());
                assertEquals(List.of(new SuiteComparison.Pair(suite, GENERATED)), job.getComparisons(),
                        action.toString());
                assertTrue(job.outcomes().get(0).startsWith("Cannot "), action + ": " + job.outcomes());
            } finally {
                setReadOnly(suite, false);
            }
        }
    }

    /** With -Dtest_scaffolding=true, replacing a suite replaces its scaffolding too. */
    @Test
    public void replacingASuiteReplacesItsScaffolding() throws Exception {
        preferences.put(EvoSuitePreferences.EXTRA_ARGUMENTS, "-Dtest_scaffolding=true");
        write(suite, EXISTING);
        write(scaffolding, "package demo;\npublic class Calculator_ESTest_scaffolding {}\n");

        IStatus result = run(job(WHOLE_CLASS, SuiteAction.REPLACE));

        assertEquals(IStatus.OK, result.getSeverity(), result::toString);
        assertEquals(GENERATED, read(suite));
        assertEquals(SCAFFOLDING, read(scaffolding));
        assertEquals(List.of("package demo;\npublic class Calculator_ESTest_scaffolding {}\n"), history(scaffolding));
    }

    /**
     * New tests that come with a different scaffolding cannot simply be added, since their
     * scaffolding lists the classes they need: both files open in the comparison.
     */
    @Test
    public void addingWithANewScaffoldingComparesBothFiles() throws Exception {
        preferences.put(EvoSuitePreferences.EXTRA_ARGUMENTS, "-Dtest_scaffolding=true");
        String oldScaffolding = "package demo;\npublic class Calculator_ESTest_scaffolding {}\n";
        write(suite, EXISTING);
        write(scaffolding, oldScaffolding);
        TestGenerationJob job = job(ONE_METHOD, SuiteAction.ADD);

        IStatus result = run(job);

        assertEquals(IStatus.OK, result.getSeverity(), result::toString);
        assertEquals(EXISTING, read(suite));
        assertEquals(oldScaffolding, read(scaffolding));
        assertEquals(List.of(new SuiteComparison.Pair(suite, GENERATED),
                new SuiteComparison.Pair(scaffolding, SCAFFOLDING)), job.getComparisons());
        assertTrue(job.outcomes().get(0).contains("Calculator_ESTest_scaffolding.java"), job.outcomes().toString());

        // With the same scaffolding, there is nothing to decide about it.
        write(scaffolding, SCAFFOLDING);
        job = job(ONE_METHOD, SuiteAction.ADD);
        result = run(job);

        assertEquals(IStatus.OK, result.getSeverity(), result::toString);
        assertEquals(List.of(), job.getComparisons());
        assertTrue(read(suite).contains("calculator0.max(7, 3)"), read(suite));
    }

    /** A suite file that EvoSuite did not generate is a test someone wrote, and stays as it is. */
    @Test
    public void neverReplacesOrAddsToAHandWrittenTest() throws Exception {
        String handWritten = "package demo;\npublic class Calculator_ESTest {\n}\n";
        for (SuiteAction action : SuiteAction.values()) {
            write(suite, handWritten);
            TestGenerationJob job = job(WHOLE_CLASS, action);

            IStatus result = run(job);

            assertEquals(IStatus.WARNING, result.getSeverity(), action + ": " + result);
            assertEquals("Calculator: the new tests would go into Calculator_ESTest.java, which is not a suite "
                    + "generated by EvoSuite and stays as it is. Choose another test class suffix or folder in the "
                    + "EvoSuite settings.", result.getChildren()[0].getMessage());
            assertEquals(handWritten, read(suite), action.toString());
            assertEquals(List.of(), job.getComparisons());
        }
        assertEquals(List.of(), runs, "EvoSuite ran for a class whose suite file is hand-written");
    }

    /** Not even when it appears while EvoSuite runs. */
    @Test
    public void neverReplacesAHandWrittenTestThatAppearedDuringTheRun() throws Exception {
        String handWritten = "package demo;\npublic class Calculator_ESTest {\n}\n";
        TestGenerationJob.Runner evoSuite = TestGenerationJob.runner;
        TestGenerationJob.runner = (commandLine, workingDirectory, environment, listener, cancelled) -> {
            Files.createDirectories(suite.getParent().getLocation().toFile().toPath());
            Files.writeString(suite.getLocation().toFile().toPath(), handWritten);
            return evoSuite.run(commandLine, workingDirectory, environment, listener, cancelled);
        };
        for (GenerationTarget target : List.of(WHOLE_CLASS, ONE_METHOD)) {
            suite.getLocation().toFile().delete();
            suite.refreshLocal(IResource.DEPTH_ZERO, null);
            TestGenerationJob job = job(target, SuiteAction.REPLACE);

            IStatus result = run(job);

            assertEquals(IStatus.WARNING, result.getSeverity(), result::toString);
            assertEquals(handWritten, read(suite));
            assertEquals(List.of(), job.getComparisons());
            assertEquals(List.of(), job.getGeneratedSuites());
        }
    }

    /**
     * A test folder or suffix in the additional arguments would make EvoSuite write elsewhere,
     * where the job would not find the new tests.
     */
    @Test
    public void theUsersArgumentsCannotMoveTheNewSuite() throws Exception {
        File elsewhere = Files.createTempDirectory("elsewhere").toFile();
        preferences.put(EvoSuitePreferences.EXTRA_ARGUMENTS,
                "-Dtest_dir=" + elsewhere.getAbsolutePath() + " -Djunit_suffix=Test");
        write(suite, EXISTING);
        TestGenerationJob job = job(ONE_METHOD, SuiteAction.ADD);

        IStatus result = run(job);

        assertEquals(IStatus.OK, result.getSeverity(), result::toString);
        assertTrue(read(suite).contains("calculator0.max(7, 3)"), read(suite));
        String[] written = elsewhere.list();
        assertEquals(0, written == null ? 0 : written.length, "EvoSuite wrote outside the temporary folder");
        elsewhere.delete();
    }

    /** A test class whose sources cannot be read loses its own run, not those of the others. */
    @Test
    public void aTargetThatCannotBePreparedLeavesTheOthers() throws Exception {
        TestProjects.addSource(project, "src/main/java", "demo.Other", CALCULATOR.replace("Calculator", "Other"));
        File root = project.getProject().getFolder("src/main/java").getLocation().toFile();
        // Two source folders, so that the job copies the sources, and one of them does not exist.
        GenerationTarget broken = GenerationTarget.extending("demo.Calculator", List.of(
                new GenerationTarget.Seed("demo.CalculatorTest", root),
                new GenerationTarget.Seed("demo.CalculatorIT", new File(root.getParentFile(), "missing"))));
        GenerationTarget fine = GenerationTarget.extending("demo.Other",
                List.of(new GenerationTarget.Seed("demo.OtherTest", root)));
        TestGenerationJob job = new TestGenerationJob(project, List.of(broken, fine),
                EvoSuitePreferences.resolve(project.getProject(), null), SuiteAction.ADD);

        IStatus result = run(job);

        assertEquals(IStatus.ERROR, result.getSeverity(), result::toString);
        assertTrue(result.getChildren()[0].getMessage().startsWith("Cannot copy the sources of CalculatorTest and "
                + "CalculatorIT"), result.toString());
        assertEquals(List.of(project.getProject().getFile("src/test/evosuite/demo/Other_ESTest.java")),
                job.getGeneratedSuites());
        assertEquals(1, runs.size());
    }

    /**
     * What a run that crashed left in the state area goes at the next run, and so do the
     * statistics EvoSuite appends to at each run.
     */
    @Test
    public void removesWhatEarlierRunsLeftBehind() throws Exception {
        File state = Activator.getDefault().getStateLocation().toFile();
        File leftover = new File(state, "generated/crashed-1/demo/Calculator_ESTest.java");
        leftover.getParentFile().mkdirs();
        Files.writeString(leftover.toPath(), GENERATED);
        File reports = new File(state, "reports/" + project.getElementName());
        File statistics = new File(reports, "statistics.csv");
        File trace = new File(reports, "llm-traces/trace.json");
        trace.getParentFile().mkdirs();
        Files.writeString(statistics.toPath(), "TARGET_CLASS\n");
        Files.writeString(trace.toPath(), "{}");

        IStatus result = run(job(WHOLE_CLASS, SuiteAction.REPLACE));

        assertEquals(IStatus.OK, result.getSeverity(), result::toString);
        assertEquals(0, new File(state, "generated").list().length, "temporary folders left behind");
        assertFalse(statistics.exists(), "statistics kept");
        assertTrue(trace.exists(), "LLM traces deleted");
    }

    /** The build path stays as it is when the settings keep the run from starting. */
    @Test
    public void checksTheSettingsBeforeChangingTheBuildPath() throws Exception {
        preferences.put(EvoSuitePreferences.LLM_PROVIDER, GenerationOptions.LLM_OLLAMA);
        var classpath = List.of(project.getRawClasspath());

        IStatus result = run(job(WHOLE_CLASS, SuiteAction.REPLACE));

        assertEquals(IStatus.ERROR, result.getSeverity(), result::toString);
        assertTrue(result.getMessage().contains("needs a model"), result.getMessage());
        assertEquals(classpath, List.of(project.getRawClasspath()));
        assertFalse(project.getProject().getFolder("src/test/evosuite").exists(), "test folder created");
        assertEquals(List.of(), runs);
    }

    private static void write(IFile file, String contents) throws Exception {
        ByteArrayInputStream in = new ByteArrayInputStream(contents.getBytes(StandardCharsets.UTF_8));
        if (file.exists()) {
            file.setContents(in, IResource.FORCE, null);
        } else {
            TestProjects.folder(file.getProject(), file.getParent().getProjectRelativePath().toString());
            file.create(in, IResource.FORCE, null);
        }
    }

    static String read(IFile file) throws Exception {
        file.refreshLocal(IResource.DEPTH_ZERO, null);
        try (InputStream in = file.getContents()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** The file's local history, newest first. */
    private static List<String> history(IFile file) throws Exception {
        List<String> states = new ArrayList<>();
        for (var state : file.getHistory(null)) {
            try (InputStream in = state.getContents()) {
                states.add(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new AssertionError(e);
            }
        }
        return states;
    }

    private static void setReadOnly(IFile file, boolean readOnly) throws Exception {
        ResourceAttributes attributes = file.getResourceAttributes();
        attributes.setReadOnly(readOnly);
        file.setResourceAttributes(attributes);
    }
}
