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

import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import com.intellij.util.xmlb.XmlSerializer;

import org.evosuite.ide.*;
import org.evosuite.intellij.run.*;
import org.evosuite.intellij.settings.*;
import org.evosuite.intellij.ui.*;

import java.nio.file.*;
import java.util.*;

public class RunHistoryTest extends LightJavaCodeInsightFixtureTestCase {
    @Override
    protected com.intellij.testFramework.fixtures.TempDirTestFixture getTempDirFixture() {
        return new com.intellij.testFramework.fixtures.impl.TempDirTestFixtureImpl();
    }

    public void testEffectiveConfigurationAndReplayPreserveOverridesWithoutCredentials() {
        var options = new GenerationOptions();
        options.llmProvider = "OPENAI";
        options.llmApproach = "SEARCH_ONLY";
        options.extraArguments =
                "-Dsearch_budget=19 -Dalgorithm=MOSA -Dllm_api_key=never-save-this -criterion"
                        + " BRANCH";
        options.pomProperties = Map.of("search_budget", "23", "llm_provider", "ANTHROPIC");
        options.pomExtraArguments = "-Dsearch_budget=31 -Dllm_max_tokens=900";
        var rows =
                EffectiveConfiguration.describe(
                        options, Map.of("searchBudget", "60"), "IDE defaults");
        var budget =
                rows.stream()
                        .filter(v -> v.property().equals("search_budget"))
                        .findFirst()
                        .orElseThrow();
        assertEquals("31", budget.value());
        assertEquals("Maven additional arguments", budget.source());
        assertEquals(
                "NONE",
                rows.stream()
                        .filter(v -> v.property().equals("llm_provider"))
                        .findFirst()
                        .orElseThrow()
                        .value());
        var saved = EffectiveConfiguration.savedValues(options);
        assertFalse(saved.toString().contains("never-save-this"));
        var replay = SettingKeys.resolve(saved);
        var args =
                new EvoSuiteCommand(
                                "java",
                                "engine",
                                "demo.Target",
                                List.of(),
                                new java.io.File("."),
                                new java.io.File("tests"),
                                new java.io.File("reports"),
                                replay)
                        .toArguments(0);
        assertEquals(
                "-Dsearch_budget=31",
                args.stream()
                        .filter(a -> a.startsWith("-Dsearch_budget="))
                        .reduce((a, b) -> b)
                        .orElseThrow());
        assertTrue(args.contains("-Dalgorithm=MOSA"));
        assertTrue(args.contains("-Dllm_max_tokens=900"));
        assertTrue(args.contains("-criterion"));
        assertFalse(replay.usePomSettings);
        assertEquals("NONE", replay.effectiveLlmProvider());
    }

    public void testHistorySurvivesSerializationAndReopensPendingComparison() throws Exception {
        var history = new RunHistory(getProject());
        String run = history.start("CLASSES", "selection");
        int index =
                history.target(
                        run,
                        "module",
                        GenerationTarget.wholeClass("demo.Target"),
                        new GenerationOptions(),
                        Path.of("/tmp/Target_ESTest.java"));
        Path staging = Files.createTempDirectory("evosuite-history-test-");
        try {
            history.beginTarget(run, index, staging);
            history.line(run, index, "java -Drandom_seed=99 -Drandom_seed=-1234");
            history.progress(run, index, 50, 75);
            var destination = staging.resolve("accepted/Target_ESTest.java");
            history.comparisons(
                    run,
                    index,
                    List.of(SuiteComparison.pending(destination, "public class Target_ESTest {}")),
                    staging);
            var restored = new RunHistory(getProject());
            restored.loadState(
                    XmlSerializer.deserialize(
                            XmlSerializer.serialize(history.getState()), RunHistory.State.class));
            var record = restored.getState().runs.getFirst();
            var target = record.targets.getFirst();
            assertEquals("Interrupted", record.status);
            assertEquals("-1234", target.seed);
            assertEquals(75, target.coverage);
            var pairs = restored.comparisons(target);
            assertEquals(1, pairs.size());
            assertEquals(destination, pairs.getFirst().destination());
            assertFalse(Files.exists(destination));
            restored.cleanup(true);
            assertEquals(
                    "Pending comparisons must not be expired", 1, restored.getState().runs.size());
            restored.reviewed(record.id, 0);
            restored.cleanup(true);
            assertTrue(restored.getState().runs.isEmpty());
            assertFalse("Cleanup cannot delete unmanaged directories", Files.notExists(staging));
        } finally {
            GenerationRun.deleteTree(staging);
        }
    }

    public void testRunsPanelKeepsActionsInsideNarrowViewport() {
        var history = RunHistory.getInstance(getProject());
        history.loadState(new RunHistory.State());
        String id = history.start("CLASSES", "demo run");
        var options = new GenerationOptions();
        options.randomSeed = "1234";
        int target =
                history.target(
                        id,
                        "app",
                        GenerationTarget.wholeClass("demo.Calculator"),
                        options,
                        Path.of("/tmp/Calculator_ESTest.java"));
        history.progress(id, target, 100, 85);
        history.outcome(id, target, "Written / updated", "Generated suite is ready", 4);
        history.finish(id, "Completed", "Finished");
        var panel = new RunResultsPanel(getProject());
        try {
            panel.setSize(400, 600);
            layout(panel);
            checkButtons(panel, 400);
            var image =
                    new java.awt.image.BufferedImage(
                            400, 600, java.awt.image.BufferedImage.TYPE_INT_RGB);
            var graphics = image.createGraphics();
            try {
                panel.printAll(graphics);
            } finally {
                graphics.dispose();
            }
            try {
                javax.imageio.ImageIO.write(
                        image,
                        "png",
                        Path.of(System.getProperty("java.io.tmpdir"), "evosuite-runs.png")
                                .toFile());
            } catch (java.io.IOException ex) {
                throw new RuntimeException(ex);
            }
        } finally {
            com.intellij.openapi.util.Disposer.dispose(panel);
        }
    }

    public void testDeletedExistingDestinationCanBeRecoveredFromSavedComparison() throws Exception {
        var history = new RunHistory(getProject());
        String run = history.start("CLASSES", "recover");
        var file =
                myFixture
                        .addFileToProject("recover/Recover_ESTest.java", "class Recover_ESTest {}")
                        .getVirtualFile();
        var doc =
                com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(file);
        Path destination = file.toNioPath(),
                staging = Files.createTempDirectory("evosuite-recover-");
        int index =
                history.target(
                        run,
                        "module",
                        GenerationTarget.wholeClass("Recover"),
                        new GenerationOptions(),
                        destination);
        try {
            history.comparisons(
                    run,
                    index,
                    List.of(
                            new SuiteComparison.Pair(
                                    "Recover_ESTest.java",
                                    doc,
                                    "class Recover_ESTest { int kept; }")),
                    staging);
            history.outcome(run, index, "Pending comparison", "", 1);
            history.finish(run, "Completed", "Review comparisons");
            com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(
                    getProject(),
                    () -> {
                        try {
                            file.delete(this);
                        } catch (java.io.IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
            var pairs = history.comparisons(history.getState().runs.getFirst().targets.getFirst());
            assertEquals(1, pairs.size());
            assertEquals(destination, pairs.getFirst().destination());
            assertFalse(Files.exists(destination));
            history.reviewed(run, index);
            var target = history.getState().runs.getFirst().targets.getFirst();
            assertEquals("Review closed", target.status);
            assertTrue(target.comparisons.isEmpty());
            assertFalse(
                    "Finishing review must not apply generated suggestions",
                    Files.exists(destination));
        } finally {
            GenerationRun.deleteTree(staging);
        }
    }

    public void testLaterModuleFailureDoesNotEraseCompletedTargetOutcomes() {
        var history = new RunHistory(getProject());
        String id = history.start("CLASSES", "module");
        int completed =
                history.target(
                        id,
                        "module",
                        GenerationTarget.wholeClass("Complete"),
                        new GenerationOptions(),
                        Path.of("/tmp/Complete_ESTest.java"));
        int pending =
                history.target(
                        id,
                        "module",
                        GenerationTarget.wholeClass("Pending"),
                        new GenerationOptions(),
                        Path.of("/tmp/Pending_ESTest.java"));
        history.outcome(id, completed, "Written / updated", "", 2);
        history.failUnfinished(id, List.of(completed, pending), "Classpath resolution failed");
        var targets = history.getState().runs.getFirst().targets;
        assertEquals("Written / updated", targets.get(completed).status);
        assertEquals(2, targets.get(completed).tests);
        assertEquals("Failed", targets.get(pending).status);
    }

    public void testHistoryAndReplayRemoveOtherCredentialSpellingsButKeepTokenLimits() {
        var options = new GenerationOptions();
        options.extraArguments =
                "-DapiKey=private1 -DaccessToken=private2 -Dtoken=private3"
                        + " -Dproxy_password=private4 -Dllm_max_tokens=2048";
        String saved = EffectiveConfiguration.savedValues(options).toString();
        assertFalse(saved, saved.contains("private"));
        assertTrue(saved, saved.contains("llm_max_tokens=2048"));
        String displayed = EffectiveConfiguration.describe(options, Map.of(), "IDE").toString();
        assertFalse(displayed, displayed.contains("private"));
    }

    public void testRetainedSettingsAreClampedAndMavenReplayIsUnavailable() {
        var history = new RunHistory(getProject());
        var state = new RunHistory.State();
        state.retentionDays = 0;
        history.loadState(state);
        assertEquals(1, history.getState().retentionDays);
        history.retention(Integer.MAX_VALUE);
        assertEquals(3650, history.getState().retentionDays);
        String id = history.start("CLASSES", "maven");
        int target =
                history.target(
                        id,
                        "app",
                        GenerationTarget.wholeClass("Example"),
                        new GenerationOptions(),
                        Path.of("/tmp/Example_ESTest.java"));
        history.outcome(id, target, "Delegated to Maven", "", -1);
        assertFalse(
                history.getState().runs.getFirst().targets.get(target).replayUnavailable.isBlank());
    }

    public void testRunsSortNumbersAndKeepSelectionWhileRefreshing() {
        var history = RunHistory.getInstance(getProject());
        history.loadState(new RunHistory.State());
        String id = history.start("CLASSES", "numbers");
        for (String name : List.of("One", "Two"))
            history.target(
                    id,
                    "app",
                    GenerationTarget.wholeClass("demo." + name),
                    new GenerationOptions(),
                    Path.of("/tmp/" + name + "_ESTest.java"));
        history.outcome(id, 0, "Written / updated", "", 2);
        history.outcome(id, 1, "Written / updated", "", 10);
        history.finish(id, "Completed", "Finished");
        var state = history.getState();
        var targets = state.runs.getFirst().targets;
        targets.get(0).started = 1000;
        targets.get(0).finished = 101000;
        targets.get(0).coverage = 9;
        targets.get(1).started = 1000;
        targets.get(1).finished = 3000;
        targets.get(1).coverage = 100;
        history.loadState(state);
        var panel = new RunResultsPanel(getProject());
        try {
            var table = findTable(panel);
            assertEquals(100L, table.getModel().getValueAt(0, 4));
            table.getRowSorter()
                    .setSortKeys(
                            List.of(
                                    new javax.swing.RowSorter.SortKey(
                                            3, javax.swing.SortOrder.ASCENDING)));
            assertEquals(2, table.getValueAt(0, 3));
            assertEquals(10, table.getValueAt(1, 3));
            table.setRowSelectionInterval(1, 1);
            panel.refresh();
            assertTrue(table.getValueAt(table.getSelectedRow(), 0).toString().contains("Two"));
            assertEquals(3, table.getRowSorter().getSortKeys().getFirst().getColumn());
            assertTrue(table.getModel().getValueAt(0, 5) instanceof java.util.Date);
            String next = history.start("METHODS", "new selection");
            history.target(
                    next,
                    "app",
                    GenerationTarget.wholeClass("demo.New"),
                    new GenerationOptions(),
                    Path.of("/tmp/New_ESTest.java"));
            panel.refresh();
            assertTrue(table.getValueAt(table.getSelectedRow(), 0).toString().contains("New"));
            history.finish(next, "Cancelled", "test finished");
        } finally {
            com.intellij.openapi.util.Disposer.dispose(panel);
        }
    }

    public void testQueuedTargetsHaveNoExecutionTimeAndCannotRunStaleTests() {
        var history = RunHistory.getInstance(getProject());
        history.loadState(new RunHistory.State());
        var file =
                myFixture
                        .addFileToProject("old/Old_ESTest.java", "public class Old_ESTest {}")
                        .getVirtualFile();
        String id = history.start("CLASSES", "queued");
        history.target(
                id,
                "app",
                GenerationTarget.wholeClass("Old"),
                new GenerationOptions(),
                file.toNioPath());
        var panel = new RunResultsPanel(getProject());
        try {
            assertEquals(-1L, findTable(panel).getModel().getValueAt(0, 4));
            assertFalse(findButton(panel, "Run tests").isEnabled());
        } finally {
            com.intellij.openapi.util.Disposer.dispose(panel);
            history.finish(id, "Cancelled", "test finished");
        }
    }

    public void testFinishReviewCanBeCancelledAndThenClosesOnlyTheSuggestions() throws Exception {
        var history = RunHistory.getInstance(getProject());
        history.loadState(new RunHistory.State());
        Path staging = Files.createTempDirectory("evosuite-review-close-");
        Path destination = staging.resolve("Missing_ESTest.java");
        String id = history.start("CLASSES", "review");
        int index =
                history.target(
                        id,
                        "app",
                        GenerationTarget.wholeClass("Missing"),
                        new GenerationOptions(),
                        destination);
        history.comparisons(
                id,
                index,
                List.of(SuiteComparison.pending(destination, "class Missing_ESTest {}")),
                staging);
        history.outcome(id, index, "Pending comparison", "", 1);
        history.finish(id, "Completed", "Review comparisons");
        var panel = new RunResultsPanel(getProject());
        var previous =
                com.intellij.openapi.ui.TestDialogManager.setTestDialog(
                        message -> {
                            for (String line : message.split("\n"))
                                assertTrue(
                                        "Review prompt must fit a compact dialog: " + line,
                                        line.length() <= 88);
                            return com.intellij.openapi.ui.Messages.NO;
                        });
        try {
            findButton(panel, "Finish review…").doClick();
            assertEquals(
                    1, history.getState().runs.getFirst().targets.getFirst().comparisons.size());
            com.intellij.openapi.ui.TestDialogManager.setTestDialog(
                    message -> com.intellij.openapi.ui.Messages.YES);
            findButton(panel, "Finish review…").doClick();
            panel.refresh();
            assertEquals(
                    "Review closed", history.getState().runs.getFirst().targets.getFirst().status);
            assertTrue(history.getState().runs.getFirst().targets.getFirst().comparisons.isEmpty());
            assertFalse(findButton(panel, "Compare").isEnabled());
            assertEquals("Review complete", history.getState().runs.getFirst().phase);
            assertFalse(Files.exists(destination));
        } finally {
            com.intellij.openapi.ui.TestDialogManager.setTestDialog(previous);
            com.intellij.openapi.util.Disposer.dispose(panel);
            GenerationRun.deleteTree(staging);
        }
    }

    public void testDelegatedRunCannotBeRepeatedAsBundledGeneration() {
        var history = RunHistory.getInstance(getProject());
        history.loadState(new RunHistory.State());
        String id = history.start("CLASSES", "delegate");
        int index =
                history.target(
                        id,
                        "app",
                        GenerationTarget.wholeClass("Delegated"),
                        new GenerationOptions(),
                        Path.of("/tmp/Delegated_ESTest.java"));
        history.outcome(id, index, "Delegated to Maven", "", -1);
        history.finish(id, "Completed", "Maven completed");
        var panel = new RunResultsPanel(getProject());
        try {
            assertFalse(findButton(panel, "Repeat saved run").isEnabled());
            assertTrue(findButton(panel, "Repeat saved run").getToolTipText().contains("Maven"));
        } finally {
            com.intellij.openapi.util.Disposer.dispose(panel);
        }
    }

    public void testRunnerSelectsTheSuiteRatherThanATopLevelHelper() {
        var file =
                (com.intellij.psi.PsiJavaFile)
                        myFixture.addFileToProject(
                                "demo/Actual_ESTest.java",
                                "package demo; class Helper {} public class Actual_ESTest {}");
        var type = GeneratedTestRunner.suiteClass(file);
        assertNotNull(type);
        assertEquals("demo.Actual_ESTest", type.getQualifiedName());
        var config =
                (com.intellij.execution.junit.JUnitConfiguration)
                        GeneratedTestRunner.configuration(getProject(), type).getConfiguration();
        assertEquals("demo.Actual_ESTest", config.getPersistentData().MAIN_CLASS_NAME);
        assertEquals("class", config.getPersistentData().TEST_OBJECT);
    }

    private static javax.swing.JTable findTable(java.awt.Component c) {
        if (c instanceof javax.swing.JTable table) return table;
        if (c instanceof java.awt.Container parent)
            for (var child : parent.getComponents()) {
                var table = findTable(child);
                if (table != null) return table;
            }
        return null;
    }

    private static javax.swing.JButton findButton(java.awt.Component c, String text) {
        if (c instanceof javax.swing.JButton button && button.getText().equals(text)) return button;
        if (c instanceof java.awt.Container parent)
            for (var child : parent.getComponents()) {
                var button = findButton(child, text);
                if (button != null) return button;
            }
        return null;
    }

    private static void layout(java.awt.Container c) {
        c.doLayout();
        for (var child : c.getComponents())
            if (child instanceof java.awt.Container parent) layout(parent);
    }

    private static void checkButtons(java.awt.Container c, int width) {
        for (var child : c.getComponents()) {
            if (child instanceof javax.swing.JButton button && button.isVisible())
                assertTrue(button.getText(), button.getX() + button.getWidth() <= c.getWidth());
            if (child instanceof java.awt.Container parent) checkButtons(parent, width);
        }
    }
}
