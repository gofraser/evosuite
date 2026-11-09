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

import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.command.undo.UndoManager;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;

import org.evosuite.ide.*;
import org.evosuite.intellij.run.RunHistory;
import org.evosuite.intellij.run.SuitePlacement;
import org.evosuite.intellij.ui.SuiteComparison;

import java.io.File;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/** Real engine output, IntelliJ document placement, and isolated javac/JUnit execution. */
public class RuntimeFreeWorkflowTest extends LightJavaCodeInsightFixtureTestCase {
    private static final String CUT = "demo.Calculator";
    private static final String SUITE = "Calculator_ESTest";
    private static final String SEED =
            "package demo; import org.junit.Test; import static org.junit.Assert.*; public class"
                    + " CalculatorTest { @Test public void baseline() { Calculator c = new"
                    + " Calculator(); int result = c.sign(-1); assertEquals(-1, result); } }";
    private final List<String> log = new CopyOnWriteArrayList<>();
    private Path home, root;
    private boolean jupiter;
    private String junit, hamcrest, runtime;

    @Override
    protected com.intellij.testFramework.fixtures.TempDirTestFixture getTempDirFixture() {
        return new com.intellij.testFramework.fixtures.impl.TempDirTestFixtureImpl();
    }

    public void testRuntimeFreeJUnit4WholeClassAndExtensionPlacementMatrix() throws Exception {
        checkMatrix(false);
    }

    public void testRuntimeFreeJUnit5WholeClassAndExtensionPlacementMatrix() throws Exception {
        checkMatrix(true);
    }

    private String seedSource() {
        return jupiter
                ? SEED.replace("org.junit.Test", "org.junit.jupiter.api.Test")
                        .replace("org.junit.Assert", "org.junit.jupiter.api.Assertions")
                : SEED;
    }

    private void checkMatrix(boolean useJupiter) throws Exception {
        jupiter = useJupiter;
        home = Path.of(System.getProperty("evosuite.it.javaHome", ""));
        if (!Files.isDirectory(home.resolve("bin")))
            home = Path.of(System.getProperty("java.home"));
        root = Path.of(myFixture.getTempDirPath());
        junit =
                com.intellij.util.PathUtil.getJarPathForClass(
                        jupiter
                                ? org.junit.platform.console.ConsoleLauncher.class
                                : org.junit.Test.class);
        hamcrest = com.intellij.util.PathUtil.getJarPathForClass(org.hamcrest.Matcher.class);
        runtime = System.getProperty("evosuite.runtime");
        Path source = root.resolve("demo/Calculator.java");
        Files.createDirectories(source.getParent());
        Files.writeString(
                source,
                "package demo; public class Calculator { public int sign(int n) { return n < 0 ? -1"
                        + " : 1; } public int extra(int n) { return n == 42 ? 7 : 0; } }");
        Path seed = root.resolve("demo/CalculatorTest.java");
        Files.writeString(seed, seedSource());
        compile(root, List.of(source, seed), false);

        var options = options(true);
        var whole = GenerationTarget.wholeClass(CUT);
        var fresh = generate(whole, options, "whole");
        assertRuntimeFree(fresh);
        var extension =
                GenerationTarget.extending(
                        CUT,
                        List.of(new GenerationTarget.Seed("demo.CalculatorTest", root.toFile())));
        var extended = generate(extension, options, "extension");
        assertSeeding(extended);
        assertRuntimeFree(extended);
        assertEquals("Seed source must remain unchanged", seedSource(), Files.readString(seed));
        compileAndRun(root.resolve("handwritten-extension-check"), List.of(extended.suite()));

        var partialFree =
                generate(new GenerationTarget(CUT, List.of("sign(I)I")), options, "partial-free");
        assertRuntimeFree(partialFree);
        Path freeSeedClasses = root.resolve("free-seed-classes");
        compile(freeSeedClasses, List.of(partialFree.suite()), false);
        var freeSelfExtension =
                GenerationTarget.extending(
                        CUT,
                        List.of(
                                new GenerationTarget.Seed(
                                        "demo." + SUITE,
                                        partialFree.suite().getParent().getParent().toFile())));
        String freeSeedSource = Files.readString(partialFree.suite());
        var freeExtended =
                generate(
                        freeSelfExtension,
                        options,
                        "free-self-extension",
                        List.of(root.toString(), freeSeedClasses.toString(), junit, hamcrest));
        assertSeeding(freeExtended);
        assertRuntimeFree(freeExtended);
        assertEquals(freeSeedSource, Files.readString(partialFree.suite()));
        compileAndRun(root.resolve("free-self-extension-check"), List.of(freeExtended.suite()));

        // A partial legacy suite leaves extra() uncovered for the runtime-dependent seed check.
        var legacyOptions = options(false);
        legacyOptions.extraArguments += " -Dtest_scaffolding=true -Dtest_extension_mode=false";
        var legacy =
                generate(new GenerationTarget(CUT, List.of("sign(I)I")), legacyOptions, "legacy");
        assertTrue(Files.isRegularFile(legacy.scaffolding()));
        Path legacyClasses = root.resolve("legacy-classes");
        compile(legacyClasses, List.of(legacy.suite(), legacy.scaffolding()), true);
        var legacyExtension =
                GenerationTarget.extending(
                        CUT,
                        List.of(
                                new GenerationTarget.Seed(
                                        "demo." + SUITE,
                                        legacy.suite().getParent().getParent().toFile())));
        String legacySource = Files.readString(legacy.suite());
        String legacyScaffold = Files.readString(legacy.scaffolding());
        var legacySeedOptions = options(true);
        var legacyExtended =
                generate(
                        legacyExtension,
                        legacySeedOptions,
                        "legacy-extension",
                        List.of(
                                root.toString(),
                                legacyClasses.toString(),
                                junit,
                                hamcrest,
                                runtime));
        assertSeeding(legacyExtended);
        assertRuntimeFree(legacyExtended);
        assertEquals(legacySource, Files.readString(legacy.suite()));
        assertEquals(legacyScaffold, Files.readString(legacy.scaffolding()));
        compileAndRun(root.resolve("legacy-extension-check"), List.of(legacyExtended.suite()));

        if (jupiter) {
            var modern =
                    generate(
                            new GenerationTarget(CUT, List.of("sign(I)I")),
                            options(false),
                            "modern");
            Path modernClasses = root.resolve("modern-classes");
            compile(modernClasses, List.of(modern.suite()), true);
            var modernTarget =
                    GenerationTarget.extending(
                            CUT,
                            List.of(
                                    new GenerationTarget.Seed(
                                            "demo." + SUITE,
                                            modern.suite().getParent().getParent().toFile())));
            var modernExtended =
                    generate(
                            modernTarget,
                            options(true),
                            "modern-extension",
                            List.of(
                                    root.toString(),
                                    modernClasses.toString(),
                                    junit,
                                    hamcrest,
                                    runtime));
            assertSeeding(modernExtended);
            assertRuntimeFree(modernExtended);
            compileAndRun(root.resolve("modern-extension-check"), List.of(modernExtended.suite()));
        }

        for (boolean legacyDestination : List.of(false, true)) {
            for (boolean extending : List.of(false, true)) {
                for (SuiteAction action : SuiteAction.values()) {
                    checkPlacement(
                            legacyDestination,
                            extending,
                            action,
                            legacyDestination ? legacy : partialFree,
                            extending ? (legacyDestination ? legacyExtended : freeExtended) : fresh,
                            extending
                                    ? (legacyDestination ? legacyExtension : freeSelfExtension)
                                    : whole,
                            options);
                }
            }
        }
        checkOrphanScaffolding(legacy, fresh, options);
        assertEquals(
                "Placement must never modify handwritten seed tests",
                seedSource(),
                Files.readString(seed));
    }

    private GenerationOptions options(boolean free) {
        var options = new GenerationOptions();
        options.noRuntimeDependency = free;
        options.randomSeed = "12345";
        options.testFormat = jupiter ? "JUNIT5" : "JUNIT4";
        options.searchBudget = 15;
        options.clientMemory = 512;
        options.criterion = "BRANCH";
        options.extraArguments = "-Dminimization_timeout=10 -Dassertion_timeout=5";
        return options;
    }

    private GenerationRun.Result generate(
            GenerationTarget target, GenerationOptions options, String name) throws Exception {
        return generate(target, options, name, List.of(root.toString(), junit, hamcrest));
    }

    private GenerationRun.Result generate(
            GenerationTarget target, GenerationOptions options, String name, List<String> classpath)
            throws Exception {
        log.clear();
        var result =
                GenerationRun.generate(
                        target,
                        options,
                        JavaInstallation.at(home),
                        Path.of(System.getProperty("evosuite.engine")),
                        classpath,
                        root,
                        root.resolve(name),
                        new ProgressParser.Listener() {
                            public void line(String line) {
                                log.add(line);
                            }

                            public void progress(int search, int coverage) {}
                        },
                        Map.of(),
                        () -> false);
        assertEquals(String.join("\n", log), 0, result.exitCode());
        assertTrue(String.join("\n", log), result.produced());
        assertTrue(
                String.join("\n", log),
                SuiteMerger.testCount(Files.readString(result.suite()), SUITE) > 0);
        return result;
    }

    private void assertRuntimeFree(GenerationRun.Result result) throws Exception {
        assertFalse(
                Files.readString(result.suite()),
                Files.readString(result.suite()).contains("org.evosuite."));
        assertFalse(
                "Runtime-free output must not export scaffolding",
                Files.exists(result.scaffolding()));
    }

    private void assertSeeding(GenerationRun.Result result) {
        assertNotNull(result.seeding());
        assertTrue(String.join("\n", log), result.seeding().seeded());
        assertTrue(String.join("\n", log), result.seeding().ranExistingTests());
        assertTrue(
                "Seed tests must exclude actual covered goals: " + String.join("\n", log),
                log.stream()
                        .anyMatch(
                                line ->
                                        line.matches(
                                                ".*Removing [1-9][0-9]* goals already covered.*")));
    }

    private void checkPlacement(
            boolean legacyDestination,
            boolean extending,
            SuiteAction action,
            GenerationRun.Result original,
            GenerationRun.Result fresh,
            GenerationTarget target,
            GenerationOptions options)
            throws Exception {
        String scenario =
                (legacyDestination ? "legacy" : "free")
                        + (extending ? "-extend-" : "-whole-")
                        + action;
        Path folder = root.resolve(scenario);
        Path destination = folder.resolve("demo/" + SUITE + ".java");
        var initial =
                SuitePlacement.place(
                        getProject(),
                        GenerationTarget.wholeClass(CUT),
                        options(!legacyDestination),
                        destination,
                        original,
                        SuiteAction.ADD,
                        null,
                        log::add);
        assertTrue(scenario, initial.written());
        myFixture.openFileInEditor(initial.suite());
        var document = FileDocumentManager.getInstance().getDocument(initial.suite());
        String before = document.getText();
        Path scaffold = destination.resolveSibling(SUITE + "_scaffolding.java");
        String oldScaffold = Files.exists(scaffold) ? Files.readString(scaffold) : null;
        var placed =
                SuitePlacement.place(
                        getProject(),
                        target,
                        options,
                        destination,
                        fresh,
                        action,
                        SuitePlacement.snapshot(destination),
                        log::add);
        assertFalse(scenario, placed.skipped());
        boolean comparison =
                action == SuiteAction.COMPARE || legacyDestination && action == SuiteAction.ADD;
        if (comparison) {
            assertFalse(scenario, placed.written());
            assertEquals(scenario, before, document.getText());
            assertEquals(scenario, legacyDestination ? 2 : 1, placed.comparisons().size());
            if (legacyDestination) assertEquals(oldScaffold, Files.readString(scaffold));
            acceptThroughSavedComparison(scenario, target, options, destination, placed);
        } else {
            assertTrue(scenario + ": " + log, placed.written());
            assertTrue(scenario, placed.comparisons().isEmpty());
            if (action == SuiteAction.ADD)
                assertTrue(
                        scenario,
                        SuiteMerger.testCount(document.getText(), SUITE)
                                > SuiteMerger.testCount(before, SUITE));
            else
                assertTrue(
                        scenario,
                        document.getText()
                                .stripTrailing()
                                .endsWith(Files.readString(fresh.suite()).stripTrailing()));
            if (legacyDestination) {
                assertFalse("Replace must remove obsolete scaffolding", Files.exists(scaffold));
                var editor =
                        FileEditorManager.getInstance(getProject())
                                .getSelectedEditor(initial.suite());
                var undo = UndoManager.getInstance(getProject());
                assertTrue(undo.isUndoAvailable(editor));
                undo.undo(editor);
                assertEquals(before, document.getText());
                assertEquals(
                        "Undo must restore the companion file",
                        oldScaffold,
                        Files.readString(scaffold));
                undo.redo(editor);
                assertFalse(Files.exists(scaffold));
            }
        }
        assertTrue(
                scenario,
                SuitePlacement.generated(getProject(), destination, document.getText(), SUITE));
        assertFalse(scenario, document.getText().contains("org.evosuite."));
        List<Path> sources = new ArrayList<>(List.of(destination));
        if (Files.exists(scaffold)) {
            assertTrue(
                    "Accepted obsolete scaffolding must be cleared",
                    Files.readString(scaffold).isBlank());
            sources.add(scaffold);
        }
        compileAndRun(folder.resolve("compiled"), sources);
        if (comparison) {
            var again =
                    SuitePlacement.place(
                            getProject(),
                            target,
                            options,
                            destination,
                            fresh,
                            SuiteAction.ADD,
                            SuitePlacement.snapshot(destination),
                            log::add);
            assertTrue(
                    "Accepted/cleared scaffolding must not block a later Add: " + scenario,
                    again.comparisons().isEmpty());
            assertFalse("Repeated Add should only find duplicates: " + scenario, again.written());
        }
    }

    private void acceptThroughSavedComparison(
            String scenario,
            GenerationTarget target,
            GenerationOptions options,
            Path destination,
            SuitePlacement.Placed placed)
            throws Exception {
        var history = RunHistory.getInstance(getProject());
        history.loadState(new RunHistory.State());
        String id = history.start("EXTEND", scenario);
        int index = history.target(id, "app", target, options, destination);
        history.comparisons(id, index, placed.comparisons(), root.resolve(scenario + "-review"));
        history.outcome(id, index, "Pending comparison", "Existing tests were evaluated.", 0);
        history.finish(id, "Finished", "Review generated tests");
        // Exercise persistence, not just the in-memory diff pair.
        history.loadState(
                com.intellij.util.xmlb.XmlSerializer.deserialize(
                        com.intellij.util.xmlb.XmlSerializer.serialize(history.getState()),
                        RunHistory.State.class));
        var pairs = history.comparisons(history.getState().runs.getFirst().targets.getFirst());
        for (var pair : pairs) {
            var request = SuiteComparison.request(getProject(), pair);
            assertTrue(
                    request.getUserData(
                                    com.intellij.diff.util.DiffUserDataKeys
                                            .FORCE_READ_ONLY_CONTENTS)[1]);
            if (pair.generated().isEmpty())
                assertTrue(request.getContentTitles().get(1).contains("Obsolete scaffolding"));
            if (pair.destination() != null) SuiteComparison.acceptNew(getProject(), pair);
            else {
                WriteCommandAction.runWriteCommandAction(
                        getProject(), () -> pair.existing().setText(pair.generated()));
                FileDocumentManager.getInstance().saveDocument(pair.existing());
                if (pair.generated().isEmpty() && scenario.contains("extend")) {
                    var file = FileDocumentManager.getInstance().getFile(pair.existing());
                    com.intellij.openapi.application.WriteAction.run(() -> file.delete(this));
                    assertTrue(
                            "A removed obsolete helper must not be offered for creation",
                            history
                                    .comparisons(
                                            history.getState().runs.getFirst().targets.getFirst())
                                    .stream()
                                    .noneMatch(p -> p.generated().isEmpty()));
                }
            }
        }
        history.reviewed(id, index);
        assertTrue(
                history.getState()
                        .runs
                        .getFirst()
                        .targets
                        .getFirst()
                        .detail
                        .contains("Existing tests were evaluated."));
    }

    private void checkOrphanScaffolding(
            GenerationRun.Result legacy, GenerationRun.Result fresh, GenerationOptions options)
            throws Exception {
        Path destination = root.resolve("orphan/demo/" + SUITE + ".java");
        Files.createDirectories(destination.getParent());
        Path scaffold = destination.resolveSibling(SUITE + "_scaffolding.java");
        Files.copy(legacy.scaffolding(), scaffold);
        var placed =
                SuitePlacement.place(
                        getProject(),
                        GenerationTarget.wholeClass(CUT),
                        options,
                        destination,
                        fresh,
                        SuiteAction.ADD,
                        null,
                        log::add);
        assertFalse(placed.written());
        assertFalse(Files.exists(destination));
        assertEquals(2, placed.comparisons().size());
        acceptThroughSavedComparison(
                "orphan", GenerationTarget.wholeClass(CUT), options, destination, placed);
        assertTrue(Files.readString(scaffold).isBlank());
        compileAndRun(root.resolve("orphan/compiled"), List.of(destination, scaffold));
    }

    private void compileAndRun(Path output, List<Path> sources) throws Exception {
        compile(output, sources, false);
        // Includes the original handwritten seed suite; EvoSuite is absent from this classpath.
        if (jupiter)
            run(
                    List.of(
                            home.resolve("bin/java").toString(),
                            "-cp",
                            classpath(output, false),
                            "org.junit.platform.console.ConsoleLauncher",
                            "execute",
                            "--disable-banner",
                            "--select-class",
                            "demo." + SUITE,
                            "--select-class",
                            "demo.CalculatorTest",
                            "--fail-if-no-tests"));
        else
            run(
                    List.of(
                            home.resolve("bin/java").toString(),
                            "-cp",
                            classpath(output, false),
                            "org.junit.runner.JUnitCore",
                            "demo." + SUITE,
                            "demo.CalculatorTest"));
    }

    private void compile(Path output, List<Path> sources, boolean withRuntime) throws Exception {
        Files.createDirectories(output);
        var command =
                new ArrayList<>(
                        List.of(
                                home.resolve("bin/javac").toString(),
                                "-cp",
                                classpath(output, withRuntime),
                                "-d",
                                output.toString()));
        sources.forEach(p -> command.add(p.toString()));
        run(command);
    }

    private String classpath(Path output, boolean withRuntime) {
        return String.join(File.pathSeparator, output.toString(), root.toString(), junit, hamcrest)
                + (withRuntime ? File.pathSeparator + runtime : "");
    }

    private void run(List<String> command) throws Exception {
        Path transcript = Files.createTempFile(root, "junit-compile-run-", ".log");
        Process process =
                new ProcessBuilder(command)
                        .redirectErrorStream(true)
                        .redirectOutput(transcript.toFile())
                        .start();
        try {
            boolean completed = process.waitFor(60, java.util.concurrent.TimeUnit.SECONDS);
            assertTrue("Timed out: " + command + "\n" + Files.readString(transcript), completed);
            assertEquals(command + "\n" + Files.readString(transcript), 0, process.exitValue());
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            }
            Files.deleteIfExists(transcript);
        }
    }
}
